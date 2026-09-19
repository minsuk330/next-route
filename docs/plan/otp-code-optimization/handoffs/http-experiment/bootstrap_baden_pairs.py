#!/usr/bin/env python3
"""Pair-level summaries and bootstrap for the Baden P6 runs.

The independent unit is a fresh-JVM pair, not an individual HTTP request.  This
script keeps the pair order from the P6 contract and emits diagnostic data only;
it never converts an environment-invalid run into a valid PR result.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
from pathlib import Path
from statistics import mean


PAIR_ORDER = [
    ("01", "A", "H"),
    ("02", "H", "A"),
    ("03", "H", "A"),
    ("04", "A", "H"),
    ("05", "A", "H"),
    ("06", "H", "A"),
]

# Semantic roles are content identities, not the legacy directory/JAR suffix.
A_SHA = "2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6"
H_SHA = "3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d"


def percentile(values: list[float], q: float) -> float | None:
    values = sorted(values)
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    pos = (len(values) - 1) * q
    lo = int(pos)
    hi = min(lo + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def request_data(path: Path) -> tuple[dict[int, str], dict[str, list[float]], dict[str, int]]:
    fingerprints: dict[int, str] = {}
    values: dict[str, list[float]] = {"all": [], "transit": [], "car": [], "schedule": []}
    errors = {"http": 0, "transport": 0}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if "seq" not in row:
                continue
            seq = int(row["seq"])
            fingerprints[seq] = row.get("responseFingerprint") or ""
            elapsed = float(row["elapsedMs"])
            values["all"].append(elapsed)
            values["schedule"].append(float(row["scheduleToCompletionMs"]))
            values["transit" if row.get("category") == "transit" else "car"].append(elapsed)
            errors["http"] += row.get("httpStatus") != 200
            errors["transport"] += bool(row.get("transportError"))
    return fingerprints, values, errors


def vm_counter(path: Path, key: str) -> int | None:
    if not path.exists():
        return None
    match = re.search(rf"^{re.escape(key)}:\s+(\d+)", path.read_text(errors="ignore"), re.MULTILINE)
    return int(match.group(1)) if match else None


def status(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    try:
        for line in (path / "status.txt").read_text(errors="replace").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                result[key] = value
    except OSError:
        pass
    return result


def semantic_variant(path: Path) -> str | None:
    """Classify a run by verified JAR content: A=flat, H=TripTimes refs."""
    st = status(path)
    digest = st.get("jarSha256", "")
    if digest == A_SHA:
        return "A"
    if digest == H_SHA:
        return "H"
    jar = st.get("jar", "")
    if jar.endswith("/otp-h-only.jar"):
        return "A"
    if jar.endswith("/otp-baseline.jar"):
        return "H"
    return None


def process_values(path: Path, field: str) -> list[float]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as handle:
        return [float(row[field]) for row in csv.DictReader(handle) if row.get(field) not in (None, "")]


def gc_summary(path: Path) -> dict:
    text = path.read_text(errors="ignore") if path.exists() else ""
    pause = [float(value) for value in re.findall(r"\[gc\s*\].*Pause [^\n]*\s([0-9]+(?:\.[0-9]+)?)ms$", text, re.MULTILINE)]
    return {
        "pauseEvents": len(pause),
        "pauseSumMs": sum(pause),
        "pauseP95Ms": percentile(pause, 0.95),
        "pauseMaxMs": max(pause) if pause else None,
        "fullPauseEvents": len(re.findall(r"\[gc\s*\].*Pause Full", text)),
        "failureLines": len(re.findall(r"allocation failure|to-space exhausted|evacuation failure", text, re.IGNORECASE)),
    }


def run_summary(path: Path) -> dict:
    fingerprints, values, errors = request_data(path / "requests.jsonl")

    def metric(items: list[float]) -> dict:
        return {
            "n": len(items),
            "meanMs": mean(items) if items else None,
            "p50Ms": percentile(items, 0.50),
            "p95Ms": percentile(items, 0.95),
            "p99Ms": percentile(items, 0.99),
            "maxMs": max(items) if items else None,
        }

    rss = process_values(path / "process.csv", "rssKiB")
    eu = process_values(path / "process.csv", "EU")
    ou = process_values(path / "process.csv", "OU")
    result = {
        "run": path.name,
        "service": metric(values["all"]),
        "transitService": metric(values["transit"]),
        "carService": metric(values["car"]),
        "scheduleToCompletion": metric(values["schedule"]),
        "errors": errors,
        "rssMiB": metric([x / 1024 for x in rss]),
        "heapUsedMiB": {"EU": metric([x / 1024 for x in eu]), "OU": metric([x / 1024 for x in ou])},
        "gc": gc_summary(path / "gc.log"),
        "host": {},
        "fingerprints": fingerprints,
    }
    for key in ("Pageouts", "Swapins", "Swapouts"):
        before = vm_counter(path / "host-before-vmstat.txt", key)
        after = vm_counter(path / "host-after-vmstat.txt", key)
        result["host"][key] = after - before if before is not None and after is not None else None
    return result


def run_key(path: Path) -> str:
    """Keep correction A/H distinct; ordinary pair dirs are already unique."""
    if path.parent.name == "pair01-correction":
        return f"pair01-correction-{path.name}"
    return path.name


def bootstrap(values: list[float], repetitions: int, seed: int) -> dict:
    rng = random.Random(seed)
    if not values:
        return {"n": 0, "estimate": None, "ci95": [None, None]}
    estimates = []
    for _ in range(repetitions):
        sample = [values[rng.randrange(len(values))] for _ in values]
        estimates.append(math.exp(mean(sample)))
    estimates.sort()
    return {
        "n": len(values),
        "estimate": math.exp(mean(values)),
        "ci95": [percentile(estimates, 0.025), percentile(estimates, 0.975)],
        "logRatios": values,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bootstrap-repetitions", type=int, default=10_000)
    args = parser.parse_args()
    runs: dict[str, dict] = {}
    pair_data: list[dict] = []
    for pair, first, second in PAIR_ORDER:
        # The original pair01-H had a verified port collision.  Prefer the
        # fresh A->H correction directory once it exists; otherwise leave pair
        # 01 out rather than silently using the invalid records.
        if pair == "01" and (args.root / "pair01-correction/A").exists() and (args.root / "pair01-correction/H").exists():
            first_path = args.root / "pair01-correction/A"
            second_path = args.root / "pair01-correction/H"
        elif pair == "01":
            continue
        else:
            first_path = args.root / f"pair{pair}-{first}"
            second_path = args.root / f"pair{pair}-{second}"
        if not (first_path / "requests.jsonl").exists() or not (second_path / "requests.jsonl").exists():
            continue
        first_summary = run_summary(first_path)
        second_summary = run_summary(second_path)
        first_name = run_key(first_path)
        second_name = run_key(second_path)
        first_summary["run"] = first_name
        second_summary["run"] = second_name
        runs[first_name] = first_summary
        runs[second_name] = second_summary
        fp_mismatch = sum(first_summary["fingerprints"].get(k) != second_summary["fingerprints"].get(k) for k in first_summary["fingerprints"])
        actual = {
            semantic_variant(first_path): first_summary,
            semantic_variant(second_path): second_summary,
        }
        if "A" not in actual or "H" not in actual:
            continue
        ratio = {}
        for key in ("p50Ms", "p95Ms", "p99Ms", "maxMs"):
            h = actual["H"]["transitService"][key]
            a = actual["A"]["transitService"][key]
            ratio[key] = h / a if h and a else None
        pair_data.append({
            "pair": pair,
            "first": semantic_variant(first_path),
            "second": semantic_variant(second_path),
            "firstRun": first_name,
            "secondRun": second_name,
            "fingerprintMismatch": fp_mismatch,
            "ratiosHOverA": ratio,
        })

    bootstrap_data = {}
    for key in ("p50Ms", "p95Ms", "p99Ms", "maxMs"):
        logs = [math.log(pair["ratiosHOverA"][key]) for pair in pair_data if pair["ratiosHOverA"][key]]
        bootstrap_data[key] = bootstrap(logs, args.bootstrap_repetitions, 20260916)
    result = {"runs": runs, "pairs": pair_data, "bootstrapTransitHOverA": bootstrap_data}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
