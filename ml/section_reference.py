"""Build the per-(route, section) reference traversal table from archived positions.

    uv run python section_reference.py --from 2026-06-12 --to 2026-11-02

Output: ``$DATA_DIR/section_reference/data.parquet`` (route_id, k, median_seconds, n).
Positions only — no labels — so it is safe to build over the whole archive. train.py
copies it next to the model (``encoding_tables/section_reference.parquet``) and serve
reads it from there, so the route-pace features are computed from one table on both
sides.
"""

from __future__ import annotations

import argparse
import sys
from datetime import date, timedelta
from pathlib import Path

import polars as pl

from build_dataset import partition_dir, position_frames
from route_pace import build_reference
from split import load_data_dir


def reference_path(data_dir: Path) -> Path:
    return data_dir / "section_reference" / "data.parquet"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from", dest="from_date", required=True)
    parser.add_argument("--to", dest="to_date", required=True)
    parser.add_argument("--min-events", type=int, default=20)
    args = parser.parse_args()
    data_dir = load_data_dir()
    histories: list[pl.DataFrame] = []
    current, end = date.fromisoformat(args.from_date), date.fromisoformat(args.to_date)
    while current <= end:
        path = partition_dir(data_dir, "bus_position", current) / "data.parquet"
        if path.exists():
            frame = (
                position_frames(path)["prepared"]
                .select("route_id", "vehicle_identity", "snapshot_at", "current_section_order")
                .collect(engine="streaming")
            )
            histories.append(frame)
            print(f"[reference] {current}: {frame.height:,} positions", flush=True)
        current += timedelta(days=1)
    reference = build_reference(histories, args.min_events)
    out = reference_path(data_dir)
    out.parent.mkdir(parents=True, exist_ok=True)
    reference.write_parquet(out)
    print(f"[reference] routes={reference['route_id'].n_unique()} sections={reference.height} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
