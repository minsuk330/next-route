#!/usr/bin/env python3
"""Write the reproducibility manifest for the Baden P6 HTTP experiment."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path


PAIR_ORDER = [
    ("01", "A", "H"),
    ("02", "H", "A"),
    ("03", "H", "A"),
    ("04", "A", "H"),
    ("05", "A", "H"),
    ("06", "H", "A"),
]

# Semantic roles are content identities, not the legacy JAR basenames.
A_SHA = "2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6"
H_SHA = "3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d"


def sha256(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def status(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if not path.exists():
        return result
    for line in path.read_text(errors="replace").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
    return result


def semantic_variant(st: dict[str, str]) -> str | None:
    """Return A (upstream flat arrays) or H (optimized references) by content."""
    digest = st.get("jarSha256", "")
    if digest == A_SHA:
        return "A"
    if digest == H_SHA:
        return "H"
    jar = st.get("jar", "")
    # Fallback is intentionally marked by the legacy basename only when a
    # status file predates jarSha256; new runs must always carry the hash.
    if jar.endswith("/otp-h-only.jar"):
        return "A"
    if jar.endswith("/otp-baseline.jar"):
        return "H"
    return None


def semantic_role(st: dict[str, str]) -> str | None:
    role = st.get("role")
    variant = semantic_variant(st)
    if not role or variant is None:
        return role
    prefix = re.sub(r"-(?:A|H)-(?:baseline|only)$", "", role)
    suffix = "baseline" if variant == "A" else "only"
    return f"{prefix}-{variant}-{suffix}"


def run_key(path: Path) -> str:
    if path.parent.name == "pair01-correction":
        return f"pair01-correction-{path.name}"
    return path.name


def request_meta(path: Path) -> dict:
    try:
        with (path / "requests.jsonl").open(encoding="utf-8") as handle:
            first = json.loads(next(handle))
        return first.get("meta", {})
    except (OSError, StopIteration, json.JSONDecodeError):
        return {}


def run_dirs(root: Path) -> list[Path]:
    result: list[Path] = []
    for pair, first, second in PAIR_ORDER:
        if pair == "01":
            correction = [root / "pair01-correction" / "A", root / "pair01-correction" / "H"]
            # If the explicitly scheduled correction was not run, retain the
            # original pair01 records in the manifest as excluded/invalid
            # evidence instead of silently omitting their hashes.
            if all(path.is_dir() for path in correction):
                result.extend(correction)
            else:
                result.extend([root / "pair01-A", root / "pair01-H"])
        else:
            result.extend([root / f"pair{pair}-{first}", root / f"pair{pair}-{second}"])
    return [path for path in result if path.is_dir()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.root
    try:
        summaries = json.loads((root / "p6-run-summaries.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        summaries = {}

    assets = {
        # These files retain misleading legacy basenames; the content SHA is
        # the canonical role identity.
        "baselineJar": Path("/private/tmp/otp-baden-build-LgbuLS/otp-h-only.jar"),
        "hOnlyJar": Path("/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar"),
        "graph": Path("/private/tmp/otp-baden-memory-FRsabG/graph/graph.obj"),
        "corpus": Path("/private/tmp/otp-baden-memory-FRsabG/official/A-rerun/travelSearch.csv"),
    }
    first_meta: dict = {}
    runs: dict[str, dict] = {}
    for path in run_dirs(root):
        key = run_key(path)
        st = status(path / "status.txt")
        first_meta = first_meta or request_meta(path)
        command = st.get("command")
        if not command:
            command = (
                f"{st.get('jvm', '/opt/homebrew/opt/openjdk@26/bin/java')} "
                f"{st.get('jvmFlags', '-Xms512m -Xmx10g -XX:+UseG1GC')} -jar {st.get('jar', '')} "
                f"--load --bindAddress 127.0.0.1 --port 18080 {st.get('graph', '')}"
            )
        runs[key] = {
            "path": str(path),
            "role": semantic_role(st),
            "jar": st.get("jar"),
            "jarSha256": st.get("jarSha256"),
            "graphSha256": st.get("graphSha256"),
            "corpusSha256": st.get("corpusSha256"),
            "jvm": st.get("jvm"),
            "jvmFlags": st.get("jvmFlags"),
            "cwd": st.get("cwd", "(not recorded; see canonical command)"),
            "command": command,
            "startedAt": st.get("startedAt"),
            "finishedAt": st.get("finishedAt"),
            "ready": st.get("ready"),
            "generatorExit": st.get("generatorExit"),
            "serverExit": st.get("serverExit"),
            "requestMeta": request_meta(path),
            "rawFiles": {name: sha256(path / name) for name in (
                "requests.jsonl", "server.log", "generator.log", "gc.log", "process.csv",
                "pressure-events.log", "host-before-vmstat.txt", "host-after-vmstat.txt",
                "nmt-before-measurement.txt", "nmt-after-measurement.txt",
            )},
        }

    artifact_hashes: dict[str, str | None] = {}
    for name, path in assets.items():
        artifact_hashes[name] = sha256(path)
    for filename in ("p6-run-summaries.json", "p6-paired-bootstrap.json", "p6-gc-overlap.json", "p6-integrity.json"):
        artifact_hashes[filename] = sha256(root / filename)

    archives = Path("/Users/wa/golbob/개인/nextroute/.artifacts/otp-baden-p6")
    archive_hashes = {
        path.name: sha256(path)
        for path in sorted(archives.glob("pair*.tar.gz"))
    } if archives.is_dir() else {}

    no_pressure = []
    for key, run in runs.items():
        run["hostGate"] = "UNKNOWN"
        data = summaries.get(key, {})
        host = data.get("host", {})
        gc = data.get("gc", {})
        if host and gc:
            run["hostDeltas"] = {field: (host.get(field) or {}).get("delta") for field in ("Pageouts", "Swapins", "Swapouts")}
            run["fullPauseEvents"] = gc.get("fullPauseEvents")
            if all(value == 0 for value in run["hostDeltas"].values()) and gc.get("fullPauseEvents") == 0:
                run["hostGate"] = "PASS"
                no_pressure.append(key)
            else:
                run["hostGate"] = "ENVIRONMENT_INVALID"

    result = {
        "schema": "otp-baden-p6-manifest-v1",
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "environmentStatus": "ENVIRONMENT_CONTAMINATED" if len(no_pressure) != len(runs) else "NO_PRESSURE_CANDIDATE",
        "root": str(root),
        "host": {"os": "macOS", "cpu": "Apple M3 Pro", "ramGiB": 18},
        "jvm": {"java": "/opt/homebrew/opt/openjdk@26/bin/java", "version": "26.0.2.1", "flags": "-Xms512m -Xmx10g -XX:+UseG1GC -XX:+NativeMemoryTracking=summary"},
        "workload": {
            "endpoint": first_meta.get("endpoint", "http://127.0.0.1:18080/otp/gtfs/v1"),
            "querySha256": first_meta.get("querySha256"),
            "scheduleSha256": first_meta.get("scheduleSha256"),
            "corpusRows": first_meta.get("corpusRows"),
            "arrivalRate": first_meta.get("arrivalRate", 8),
            "workers": first_meta.get("workers", 32),
            "scheduleRows": first_meta.get("scheduleRows", 20000),
            "seed": first_meta.get("seed", 20260916),
            "numItineraries": first_meta.get("numItineraries", 3),
        },
        "roleDefinition": {
            "A": "upstream baseline: arrivalTimes[]/departureTimes[] flat arrays copied per request",
            "H": "optimized code: TripTimes[]/offsetsByTrip references; no flat timetable arrays",
            "A_SHA256": A_SHA,
            "H_SHA256": H_SHA,
        },
        "assets": {name: {"path": str(path), "sha256": artifact_hashes[name]} for name, path in assets.items()},
        "analysisArtifacts": artifact_hashes,
        "rawArchive": {"path": str(archives), "sha256": archive_hashes, "available": bool(archive_hashes)},
        "runOrder": [run_key(path) for path in run_dirs(root)],
        "noPressureRuns": no_pressure,
        "runs": runs,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
