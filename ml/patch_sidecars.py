"""Recompute the calendar (holiday) and weather columns of existing dataset parts.

build_dataset.py derives these from sidecars at build time; when a sidecar was
missing or has changed, this rewrites just those columns in place instead of
rebuilding the whole partition (the position/label join is untouched).

    uv run python patch_sidecars.py --from 2026-06-12 --to 2026-11-02
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date, timedelta
from pathlib import Path

import polars as pl

from build_dataset import (
    WEATHER_COLUMNS,
    attach_weather,
    calendar_columns,
    dataset_columns,
    load_holidays,
    load_weather,
    partition_dir,
    write_manifest,
)
from split import load_data_dir

CALENDAR_COLUMNS = ["is_commute_morning", "is_commute_evening", "is_late_night",
                    "is_holiday", "is_pre_holiday", "is_post_holiday"]


def patch_partition(data_dir: Path, service_date: date, holidays: list[date]) -> int:
    output_dir = partition_dir(data_dir, "dataset", service_date)
    parts = sorted(output_dir.glob("part-*.parquet"))
    if not parts:
        return 0
    weather = load_weather(data_dir, service_date)
    for part in parts:
        frame = pl.read_parquet(part).drop([c for c in [*CALENDAR_COLUMNS, *WEATHER_COLUMNS] if c in pl.read_parquet_schema(part)])
        frame = frame.with_columns(calendar_columns(holidays))
        frame = attach_weather(frame.lazy(), weather).select(dataset_columns()).collect()
        tmp = part.with_suffix(".parquet.tmp")
        frame.write_parquet(tmp)
        tmp.replace(part)
    manifest_path = output_dir / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        manifest["sidecars_patched"] = {
            "holiday_count": len(holidays),
            "weather": weather is not None,
        }
        write_manifest(manifest_path, manifest)
    return len(parts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from", dest="from_date", required=True)
    parser.add_argument("--to", dest="to_date", required=True)
    args = parser.parse_args()
    data_dir = load_data_dir()
    holidays = load_holidays(data_dir)
    print(f"[patch] holidays: {[d.isoformat() for d in holidays]}")
    start, end = date.fromisoformat(args.from_date), date.fromisoformat(args.to_date)
    current = start
    while current <= end:
        n = patch_partition(data_dir, current, holidays)
        if n:
            print(f"[patch] {current}: {n} parts", flush=True)
        current += timedelta(days=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
