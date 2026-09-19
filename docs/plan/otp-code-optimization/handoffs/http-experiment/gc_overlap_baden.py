#!/usr/bin/env python3
"""Classify Baden HTTP requests by overlap with stop-the-world GC pauses."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from pathlib import Path


START_RE = re.compile(
    r"^\[(?P<stamp>[^]]+)\]\[(?P<up>[0-9.]+)s\].*\[gc,start\].*GC\((?P<id>\d+)\) (?P<kind>Pause .*)$"
)
END_RE = re.compile(
    r"^\[(?P<stamp>[^]]+)\]\[(?P<up>[0-9.]+)s\].*\[gc\s+\].*GC\((?P<id>\d+)\) (?P<kind>Pause .*) (?P<duration>[0-9.]+)ms$"
)


def epoch_ns(stamp: str) -> int:
    # Java's unified logging may emit either ISO-8601 offsets with a colon
    # (`+09:00`) or the compact `%z` form (`+0900`).  Normalize the latter
    # before handing the value to `fromisoformat` so interrupted and completed
    # runs are analyzed identically.
    normalized = stamp
    if len(normalized) >= 5 and normalized[-5] in "+-" and normalized[-3] != ":":
        normalized = normalized[:-2] + ":" + normalized[-2:]
    return int(datetime.fromisoformat(normalized).timestamp() * 1_000_000_000)


def gc_events(path: Path) -> list[dict]:
    starts: dict[str, dict] = {}
    result: list[dict] = []
    for line in path.read_text(errors="ignore").splitlines() if path.exists() else []:
        start = START_RE.match(line)
        if start:
            starts[start["id"]] = {
                "id": start["id"],
                "startNs": epoch_ns(start["stamp"]),
                "kind": start["kind"],
            }
            continue
        end = END_RE.match(line)
        if not end:
            continue
        item = starts.pop(end["id"], None)
        if item is None:
            duration_ns = int(float(end["duration"]) * 1_000_000)
            item = {"id": end["id"], "startNs": epoch_ns(end["stamp"]) - duration_ns, "kind": end["kind"]}
        item["endNs"] = epoch_ns(end["stamp"])
        item["durationMs"] = float(end["duration"])
        result.append(item)
    return result


def requests(path: Path) -> list[dict]:
    result = []
    for line in path.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if "seq" in row:
            result.append(row)
    return result


def classify(run: Path) -> dict:
    pauses = gc_events(run / "gc.log")
    rows = requests(run / "requests.jsonl")
    groups = {"GC_FREE": [], "GC_OVERLAP": []}
    for row in rows:
        start = int(row["sentAtEpochNs"])
        end = int(row["completedAtEpochNs"])
        overlaps = [pause for pause in pauses if start < pause["endNs"] and end > pause["startNs"]]
        group = "GC_OVERLAP" if overlaps else "GC_FREE"
        groups[group].append(row)

    def metric(items: list[dict]) -> dict:
        values = sorted(float(row["elapsedMs"]) for row in items)
        if not values:
            return {"n": 0, "p50Ms": None, "p95Ms": None, "p99Ms": None, "maxMs": None}
        def p(q: float) -> float:
            pos = (len(values) - 1) * q
            lo = int(pos); hi = min(lo + 1, len(values) - 1)
            return values[lo] + (values[hi] - values[lo]) * (pos - lo)
        return {"n": len(values), "p50Ms": p(.5), "p95Ms": p(.95), "p99Ms": p(.99), "maxMs": values[-1]}

    return {
        "run": run.name,
        "gcPauseEvents": len(pauses),
        "gcPauseSumMs": sum(item["durationMs"] for item in pauses),
        "gcPauseMaxMs": max((item["durationMs"] for item in pauses), default=None),
        "requests": {name: metric(items) for name, items in groups.items()},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps({run.name: classify(run) for run in args.run}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
