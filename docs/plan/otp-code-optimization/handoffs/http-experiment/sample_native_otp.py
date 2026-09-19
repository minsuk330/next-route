#!/usr/bin/env python3
"""Low-cost macOS sampler for a native OTP JVM.

The CSV keeps process RSS/CPU, jstat GC/heap counters, macOS memory pressure and
vm_stat page counters in one monotonic timeline. It is an experiment artifact, not
part of OTP production code.
"""

from __future__ import annotations

import argparse
import csv
import re
import subprocess
import time
from pathlib import Path


JSTAT_FIELDS = [
    "S0C", "S1C", "S0U", "S1U", "EC", "EU", "OC", "OU", "MC", "MU",
    "CCSC", "CCSU", "YGC", "YGCT", "FGC", "FGCT", "CGC", "CGCT", "GCT",
]


def command(*args: str) -> str:
    try:
        return subprocess.run(args, check=False, capture_output=True, text=True, timeout=2).stdout.strip()
    except Exception:
        return ""


def process_stats(pid: int) -> tuple[str, str, str]:
    output = command("ps", "-p", str(pid), "-o", "rss=", "-o", "vsz=", "-o", "%cpu=")
    fields = output.split()
    return (fields + [""] * 3)[:3]


def jstat_stats(pid: int) -> dict[str, str]:
    output = command("/opt/homebrew/opt/openjdk@26/bin/jstat", "-gc", str(pid))
    lines = output.splitlines()
    if len(lines) < 2:
        return {}
    values = lines[-1].split()
    if len(values) != len(JSTAT_FIELDS):
        return {}
    return dict(zip(JSTAT_FIELDS, values))


def vm_stats() -> tuple[str, str, str, str, str, str]:
    output = command("vm_stat")
    values = {}
    for line in output.splitlines():
        match = re.match(r"([^:]+):\s+(\d+)", line)
        if match:
            values[match.group(1)] = match.group(2)
    pressure = command("memory_pressure", "-Q")
    free = re.search(r"free percentage:\s*(\d+)%", pressure)
    swap = command("sysctl", "-n", "vm.swapusage")
    return (
        values.get("Pageins", ""),
        values.get("Pageouts", ""),
        values.get("Swapins", ""),
        values.get("Swapouts", ""),
        free.group(1) if free else "",
        swap,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval-s", type=float, default=1.0)
    args = parser.parse_args()
    if args.interval_s <= 0:
        parser.error("--interval-s must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "epochNs", "monotonicNs", "rssKiB", "vszKiB", "cpuPct",
        "pageins", "pageouts", "swapins", "swapouts", "memoryPressureFreePct", "swapUsage", "pidAlive",
        *JSTAT_FIELDS,
    ]
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        while True:
            epoch = time.time_ns()
            mono = time.monotonic_ns()
            rss, vsz, cpu = process_stats(args.pid)
            js = jstat_stats(args.pid)
            pageins, pageouts, swapins, swapouts, pressure, swap = vm_stats()
            row = {
                "epochNs": epoch,
                "monotonicNs": mono,
                "rssKiB": rss,
                "vszKiB": vsz,
                "cpuPct": cpu,
                "pageins": pageins,
                "pageouts": pageouts,
                "swapins": swapins,
                "swapouts": swapouts,
                "memoryPressureFreePct": pressure,
                "swapUsage": swap,
                "pidAlive": "1" if rss else "0",
                **{key: js.get(key, "") for key in JSTAT_FIELDS},
            }
            writer.writerow(row)
            handle.flush()
            time.sleep(args.interval_s)


if __name__ == "__main__":
    raise SystemExit(main())
