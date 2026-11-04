"""Restore the raw arrival label as the training target in built partitions.

Partitions built with ``--refine-arrival`` carry both ``corrected_arrival_at`` (refined)
and ``corrected_arrival_at_raw``; this puts the raw value back as the target without
rebuilding (the measured shift stays in ``arrival_refined_seconds``).

    uv run python patch_target_raw.py --from 2026-06-12 --to 2026-11-02
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date, timedelta
from pathlib import Path

import polars as pl

from build_dataset import partition_dir, write_manifest
from split import load_data_dir


def patch_partition(data_dir: Path, service_date: date) -> int:
    output_dir = partition_dir(data_dir, "dataset", service_date)
    parts = sorted(output_dir.glob("part-*.parquet"))
    if not parts:
        return 0
    for part in parts:
        if "corrected_arrival_at_raw" not in pl.read_parquet_schema(part):
            continue
        frame = pl.read_parquet(part).with_columns(
            pl.col("corrected_arrival_at_raw").alias("corrected_arrival_at")
        ).with_columns(
            (pl.col("corrected_arrival_at") - pl.col("snapshot_at"))
            .dt.total_seconds()
            .alias("corrected_target_seconds_to_arrival")
        )
        tmp = part.with_suffix(".parquet.tmp")
        frame.write_parquet(tmp)
        tmp.replace(part)
    manifest_path = output_dir / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        manifest["refine_arrival"] = False
        write_manifest(manifest_path, manifest)
    return len(parts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from", dest="from_date", required=True)
    parser.add_argument("--to", dest="to_date", required=True)
    args = parser.parse_args()
    data_dir = load_data_dir()
    current, end = date.fromisoformat(args.from_date), date.fromisoformat(args.to_date)
    while current <= end:
        n = patch_partition(data_dir, current)
        if n:
            print(f"[raw-target] {current}: {n} parts", flush=True)
        current += timedelta(days=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
