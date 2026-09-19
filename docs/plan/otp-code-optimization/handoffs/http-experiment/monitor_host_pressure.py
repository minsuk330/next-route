#!/usr/bin/env python3
"""Record the first and subsequent host swap/pageout deltas during a run."""

from __future__ import annotations

import argparse
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


def command(*args: str) -> str:
    try:
        return subprocess.run(args, capture_output=True, text=True, check=False, timeout=3).stdout.strip()
    except Exception:
        return ""


def counters() -> dict[str, int]:
    output = command("vm_stat")
    result: dict[str, int] = {}
    for line in output.splitlines():
        match = re.match(r"(Pageins|Pageouts|Swapins|Swapouts):\s+(\d+)", line)
        if match:
            result[match.group(1)] = int(match.group(2))
    return result


def baseline(path: Path) -> dict[str, int]:
    result: dict[str, int] = {}
    for line in path.read_text(errors="ignore").splitlines():
        match = re.match(r"(Pageins|Pageouts|Swapins|Swapouts):\s+(\d+)", line)
        if match:
            result[match.group(1)] = int(match.group(2))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--interval-s", type=float, default=1.0)
    args = parser.parse_args()
    before = baseline(args.run_dir / "host-before-vmstat.txt")
    out = args.run_dir / "pressure-events.log"
    last_report: dict[str, int] | None = None
    with out.open("w", encoding="utf-8") as handle:
        while True:
            current = counters()
            delta = {key: current.get(key, 0) - before.get(key, 0) for key in ("Pageins", "Pageouts", "Swapins", "Swapouts")}
            pressure = command("memory_pressure", "-Q").replace("\n", " | ")
            swap = command("sysctl", "-n", "vm.swapusage")
            notable = any(delta[key] > 0 for key in ("Pageouts", "Swapins", "Swapouts"))
            notable_changed = last_report is None or any(
                delta[key] != last_report[key] for key in ("Pageouts", "Swapins", "Swapouts")
            )
            if notable_changed and notable:
                timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
                handle.write(f"{timestamp} epochNs={time.time_ns()} delta={delta} swapusage={swap!r} pressure={pressure}\n")
                handle.flush()
                last_report = delta
            time.sleep(args.interval_s)


if __name__ == "__main__":
    raise SystemExit(main())
