#!/usr/bin/env python3
"""Summarize Baden native HTTP runs without treating requests as independent pairs.

The script intentionally reports diagnostics (GC/RSS/host counters) separately from
the request latency.  It is an experiment artifact, not OTP production code.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from statistics import mean, median


def pct(values: list[float], q: float) -> float | None:
    values = sorted(values)
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    pos = (len(values) - 1) * q
    lo, hi = int(pos), min(len(values) - 1, int(pos) + 1)
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def number(value: str | None) -> float | None:
    if value in (None, "", "NaN"):
        return None
    try:
        return float(value)
    except ValueError:
        return None


def requests_summary(path: Path, keep_records: bool = False) -> dict:
    rows: list[dict] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            value = json.loads(line)
            if "seq" in value:
                rows.append(value)
    service = [float(row["elapsedMs"]) for row in rows]
    schedule = [float(row["scheduleToCompletionMs"]) for row in rows]
    transit = [row for row in rows if row.get("category") == "transit"]
    car = [row for row in rows if row.get("category") == "car"]
    status_errors = sum(row.get("httpStatus") != 200 for row in rows)
    transport_errors = sum(bool(row.get("transportError")) for row in rows)
    routing_errors = sum(
        len(((row.get("canonical") or {}).get("routingErrors") or [])) > 0
        for row in rows
    )

    def metrics(values: list[float]) -> dict[str, float | int | None]:
        return {
            "n": len(values),
            "meanMs": mean(values) if values else None,
            "p50Ms": pct(values, 0.50),
            "p95Ms": pct(values, 0.95),
            "p99Ms": pct(values, 0.99),
            "maxMs": max(values) if values else None,
        }

    result = {
        "n": len(rows),
        "seqMin": min((row["seq"] for row in rows), default=None),
        "seqMax": max((row["seq"] for row in rows), default=None),
        "service": metrics(service),
        "scheduleToCompletion": metrics(schedule),
        "transitService": metrics([float(row["elapsedMs"]) for row in transit]),
        "carService": metrics([float(row["elapsedMs"]) for row in car]),
        "httpStatusErrors": status_errors,
        "transportErrors": transport_errors,
        "routingErrorResponses": routing_errors,
    }
    if keep_records:
        result["fingerprintsBySeq"] = {str(row["seq"]): row.get("responseFingerprint") for row in rows}
        result["records"] = rows
    return result


def process_summary(path: Path) -> dict:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))

    def vals(field: str) -> list[float]:
        return [value for row in rows if (value := number(row.get(field))) is not None]

    def stat(field: str) -> dict[str, float | None]:
        values = vals(field)
        return {"median": median(values) if values else None, "p95": pct(values, 0.95), "max": max(values) if values else None}

    last = rows[-1] if rows else {}
    return {
        "samples": len(rows),
        "rssMiB": {key: value / 1024 for key, value in stat("rssKiB").items() if value is not None},
        "vszGiB": {key: value / 1024 / 1024 for key, value in stat("vszKiB").items() if value is not None},
        "cpuPct": stat("cpuPct"),
        "heapUsedMiB": {
            "EU": {key: value / 1024 for key, value in stat("EU").items() if value is not None},
            "OU": {key: value / 1024 for key, value in stat("OU").items() if value is not None},
        },
        "gcCountersLast": {field: number(last.get(field)) for field in ("YGC", "YGCT", "FGC", "FGCT", "GCT")},
    }


def vm_counter(path: Path, name: str) -> int | None:
    if not path.exists():
        return None
    match = re.search(rf"^{re.escape(name)}:\s+(\d+)", path.read_text(errors="ignore"), re.MULTILINE)
    return int(match.group(1)) if match else None


def host_summary(run: Path) -> dict:
    result = {}
    for name in ("Pageins", "Pageouts", "Swapins", "Swapouts"):
        before = vm_counter(run / "host-before-vmstat.txt", name)
        after = vm_counter(run / "host-after-vmstat.txt", name)
        result[name] = {"before": before, "after": after, "delta": after - before if before is not None and after is not None else None}
    return result


def gc_summary(path: Path) -> dict:
    text = path.read_text(errors="ignore") if path.exists() else ""
    pause_lines = re.findall(r"\[gc\s*\].*Pause [^\n]*\s([0-9]+(?:\.[0-9]+)?)ms$", text, re.MULTILINE)
    full_lines = re.findall(r"\[gc\s*\].*Pause Full[^\n]*", text)
    fail = re.findall(r"allocation failure|to-space exhausted|evacuation failure", text, re.IGNORECASE)
    pauses = [float(value) for value in pause_lines]
    return {
        "pauseEvents": len(pauses),
        "pauseSumMs": sum(pauses),
        "pauseP95Ms": pct(pauses, 0.95),
        "pauseMaxMs": max(pauses) if pauses else None,
        "fullPauseEvents": len(full_lines),
        "failureLines": len(fail),
    }


def summarize(run: Path, keep_records: bool = False) -> dict:
    return {
        "run": str(run),
        "status": (run / "status.txt").read_text(errors="ignore") if (run / "status.txt").exists() else None,
        "requests": requests_summary(run / "requests.jsonl", keep_records),
        "process": process_summary(run / "process.csv"),
        "host": host_summary(run),
        "gc": gc_summary(run / "gc.log"),
    }


def run_key(run: Path) -> str:
    """Return a stable identifier even for nested correction runs (A/H)."""
    if run.parent.name == "pair01-correction":
        return f"pair01-correction-{run.name}"
    return run.name


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--include-records", action="store_true", help="include full request records; output can be very large")
    args = parser.parse_args()
    result = {run_key(run): summarize(run, args.include_records) for run in args.run}
    encoded = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(encoded + "\n", encoding="utf-8")
    else:
        print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
