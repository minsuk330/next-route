"""Add (or recompute) the route-pace columns of built dataset partitions.

build_dataset.py computes these at build time; this rewrites just those columns
from the day's positions and the section reference table, without rebuilding.

    uv run python patch_route_pace.py --from 2026-06-12 --to 2026-11-02
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date, timedelta
from pathlib import Path

import polars as pl

from build_dataset import partition_dir, position_frames, write_manifest
from route_pace import ROUTE_PACE_FEATURES, compute_route_pace_features
from section_reference import reference_path
from split import load_data_dir


def patch_partition(data_dir: Path, service_date: date, reference: pl.DataFrame) -> int:
    output_dir = partition_dir(data_dir, "dataset", service_date)
    parts = sorted(output_dir.glob("part-*.parquet"))
    position_path = partition_dir(data_dir, "bus_position", service_date) / "data.parquet"
    if not parts or not position_path.exists():
        return 0
    positions = (
        position_frames(position_path)["prepared"]
        .select("route_id", "vehicle_identity", "snapshot_at", "current_section_order")
        .collect(engine="streaming")
    )
    for part in parts:
        frame = pl.read_parquet(part)
        frame = frame.drop([c for c in ROUTE_PACE_FEATURES if c in frame.columns])
        route = frame["route_id"].cast(pl.Utf8)[0] if frame.height else None
        history = positions.filter(pl.col("route_id").cast(pl.Utf8) == route)
        features = compute_route_pace_features(
            history,
            frame.select("route_id", "snapshot_at", "current_section_order", "target_seq"),
            reference,
        )
        frame = pl.concat([frame, features], how="horizontal")
        tmp = part.with_suffix(".parquet.tmp")
        frame.write_parquet(tmp)
        tmp.replace(part)
    manifest_path = output_dir / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        manifest["route_pace_patched"] = True
        write_manifest(manifest_path, manifest)
    return len(parts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from", dest="from_date", required=True)
    parser.add_argument("--to", dest="to_date", required=True)
    args = parser.parse_args()
    data_dir = load_data_dir()
    reference = pl.read_parquet(reference_path(data_dir))
    current, end = date.fromisoformat(args.from_date), date.fromisoformat(args.to_date)
    while current <= end:
        n = patch_partition(data_dir, current, reference)
        if n:
            print(f"[route-pace] {current}: {n} parts", flush=True)
        current += timedelta(days=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
