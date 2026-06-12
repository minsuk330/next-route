from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

import polars as pl
from dotenv import load_dotenv


SCHEMA_VERSION = 1
QUERY_VERSION = "v1"
DATASET_ARCHIVE_VERSION = "v1"


class DatasetBuildError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build bus ML dataset parquet from archived label/position parquet."
    )
    parser.add_argument("service_date", help="Service date in YYYY-MM-DD format.")
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Regenerate dataset even when parquet parts already exist.",
    )
    return parser.parse_args()


def parse_service_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise DatasetBuildError(
            f"Invalid service date '{value}'. Use YYYY-MM-DD."
        ) from exc


def load_data_dir() -> Path:
    ml_dir = Path(__file__).resolve().parent
    load_dotenv(ml_dir / ".env")

    data_dir_value = os.getenv("DATA_DIR", "./data")
    data_dir = Path(data_dir_value).expanduser()
    if not data_dir.is_absolute():
        data_dir = ml_dir / data_dir
    return data_dir


def partition_dir(data_dir: Path, table: str, service_date: date) -> Path:
    return data_dir / table / f"service_date={service_date.isoformat()}"


def require_archive(data_dir: Path, table: str, service_date: date) -> Path:
    data_path = partition_dir(data_dir, table, service_date) / "data.parquet"
    if not data_path.exists():
        raise DatasetBuildError(
            f"Missing archive: {data_path}. Run archive.py {service_date} first."
        )
    return data_path


def clean_string(name: str) -> pl.Expr:
    value = pl.col(name).cast(pl.Utf8).str.strip_chars()
    return (
        pl.when(value.is_not_null() & (value != "") & (value != "0"))
        .then(value)
        .otherwise(pl.lit(None, dtype=pl.Utf8))
    )


def count_rows(frame: pl.LazyFrame) -> int:
    return int(frame.select(pl.len()).collect().item())


def safe_ratio(numerator: pl.Expr, denominator: pl.Expr) -> pl.Expr:
    return (
        pl.when(denominator.is_not_null() & (denominator > 0))
        .then(numerator / denominator)
        .otherwise(pl.lit(None, dtype=pl.Float64))
    )


def position_frames(position_path: Path) -> dict[str, pl.LazyFrame]:
    raw = pl.scan_parquet(str(position_path))
    with_snapshot = raw.with_columns(
        [
            pl.col("data_tm")
            .cast(pl.Utf8)
            .str.strptime(pl.Datetime, "%Y%m%d%H%M%S", strict=False)
            .alias("snapshot_at"),
            clean_string("vehicle_id").alias("_vehicle_id_clean"),
            clean_string("plain_no").alias("_plain_no_clean"),
        ]
    ).with_columns(
        [
            pl.coalesce(["_vehicle_id_clean", "_plain_no_clean"]).alias(
                "vehicle_identity"
            )
        ]
    )
    valid_snapshot = with_snapshot.filter(pl.col("snapshot_at").is_not_null())
    fresh = valid_snapshot.with_columns(
        [
            (pl.col("collected_at") - pl.col("snapshot_at"))
            .dt.total_seconds()
            .alias("_freshness_seconds")
        ]
    ).filter(
        (pl.col("_freshness_seconds") >= -60)
        & (pl.col("_freshness_seconds") <= 120)
    )
    prepared = (
        fresh.filter(
            pl.col("section_order").is_not_null()
            & pl.col("vehicle_identity").is_not_null()
        )
        .with_columns(
            [
                clean_string("next_stop_id").alias("next_stop_id_clean"),
                safe_ratio(
                    pl.col("section_distance"),
                    pl.col("full_section_distance"),
                ).alias("section_progress"),
            ]
        )
        .select(
            [
                pl.col("id").alias("position_raw_id"),
                pl.col("route_id"),
                pl.col("vehicle_identity"),
                pl.col("snapshot_at"),
                pl.col("section_id").alias("current_section_id"),
                pl.col("section_order").alias("current_section_order"),
                pl.col("section_distance").alias("current_section_distance"),
                pl.col("full_section_distance").alias(
                    "current_full_section_distance"
                ),
                pl.col("section_progress"),
                pl.col("stop_flag"),
                pl.col("next_stop_id_clean").alias("next_stop_id"),
                pl.col("next_stop_time"),
                pl.col("last_stop_time"),
                pl.col("congestion"),
                pl.col("gps_x"),
                pl.col("gps_y"),
            ]
        )
    )
    return {
        "raw": raw,
        "with_snapshot": with_snapshot,
        "valid_snapshot": valid_snapshot,
        "fresh": fresh,
        "prepared": prepared,
    }


def label_frames(label_path: Path) -> dict[str, pl.LazyFrame]:
    raw = pl.scan_parquet(str(label_path))
    normalized = raw.with_columns(
        [
            pl.col("api_estimated_arrival_at").cast(pl.Datetime),
            pl.col("corrected_arrival_at").cast(pl.Datetime),
            pl.col("label_arrival_at").cast(pl.Datetime),
        ]
    )
    filtered = normalized.filter(
        (pl.col("excluded_from_training") == False)
        & pl.col("label_arrival_at").is_not_null()
        & pl.col("api_estimated_arrival_at").is_not_null()
        & pl.col("vehicle_identity").is_not_null()
    ).select(
        [
            pl.col("id").alias("arrival_label_event_id"),
            pl.col("service_date"),
            pl.col("route_id"),
            pl.col("vehicle_identity"),
            pl.col("trip_id"),
            pl.col("stop_id").alias("target_stop_id"),
            pl.col("seq").alias("target_seq"),
            pl.col("section_id").alias("target_section_id"),
            pl.col("api_estimated_arrival_at"),
            pl.col("corrected_arrival_at"),
            pl.col("label_arrival_at"),
            pl.col("label_source"),
            pl.col("label_confidence"),
            pl.col("correction_source"),
            pl.col("correction_confidence"),
            pl.col("arrival_raw_id"),
        ]
    )
    return {"raw": raw, "filtered": filtered}


def route_ids(position: pl.LazyFrame, label: pl.LazyFrame) -> list[str]:
    position_routes = set(
        position.select("route_id").unique().collect()["route_id"].to_list()
    )
    label_routes = set(label.select("route_id").unique().collect()["route_id"].to_list())
    return sorted(str(route) for route in position_routes & label_routes if route is not None)


def dataset_for_route(
    position: pl.LazyFrame,
    label: pl.LazyFrame,
    route_id: str,
    generated_at: str,
) -> pl.LazyFrame:
    position_route = position.filter(pl.col("route_id") == route_id)
    label_route = label.filter(pl.col("route_id") == route_id)

    joined = position_route.join(
        label_route,
        on=["route_id", "vehicle_identity"],
        how="inner",
    )

    with_targets = joined.with_columns(
        [
            (pl.col("api_estimated_arrival_at") - pl.col("snapshot_at"))
            .dt.total_seconds()
            .alias("api_target_seconds_to_arrival"),
            (pl.col("corrected_arrival_at") - pl.col("snapshot_at"))
            .dt.total_seconds()
            .alias("corrected_target_seconds_to_arrival"),
            (pl.col("label_arrival_at") - pl.col("snapshot_at"))
            .dt.total_seconds()
            .alias("label_target_seconds_to_arrival"),
            (pl.col("target_seq") - pl.col("current_section_order")).alias(
                "remaining_stop_count"
            ),
        ]
    )

    return (
        with_targets.filter(
            (pl.col("label_arrival_at") > pl.col("snapshot_at") + pl.duration(minutes=1))
            & (
                pl.col("label_arrival_at")
                <= pl.col("snapshot_at") + pl.duration(minutes=40)
            )
            & (pl.col("target_seq") >= pl.col("current_section_order"))
            & (pl.col("label_target_seconds_to_arrival") >= 60)
            & (pl.col("label_target_seconds_to_arrival") <= 2400)
        )
        .with_columns(
            [
                pl.col("snapshot_at").dt.hour().alias("hour_of_day"),
                pl.col("snapshot_at").dt.minute().alias("minute_of_day"),
                pl.col("snapshot_at").dt.weekday().alias("day_of_week"),
                pl.col("snapshot_at")
                .dt.weekday()
                .is_in([6, 7])
                .alias("is_weekend"),
                pl.lit(SCHEMA_VERSION).alias("schema_version"),
                pl.lit(generated_at).alias("generated_at"),
            ]
        )
        .select(dataset_columns())
    )


def dataset_columns() -> list[str]:
    return [
        "service_date",
        "snapshot_at",
        "route_id",
        "vehicle_identity",
        "current_section_id",
        "current_section_order",
        "current_section_distance",
        "current_full_section_distance",
        "section_progress",
        "stop_flag",
        "next_stop_id",
        "next_stop_time",
        "last_stop_time",
        "congestion",
        "gps_x",
        "gps_y",
        "target_stop_id",
        "target_seq",
        "target_section_id",
        "remaining_stop_count",
        "hour_of_day",
        "minute_of_day",
        "day_of_week",
        "is_weekend",
        "api_target_seconds_to_arrival",
        "corrected_target_seconds_to_arrival",
        "label_target_seconds_to_arrival",
        "api_estimated_arrival_at",
        "corrected_arrival_at",
        "label_arrival_at",
        "label_source",
        "label_confidence",
        "correction_source",
        "correction_confidence",
        "position_raw_id",
        "arrival_label_event_id",
        "arrival_raw_id",
        "trip_id",
        "schema_version",
        "generated_at",
    ]


def remove_dataset_outputs(output_dir: Path) -> None:
    if output_dir.exists():
        shutil.rmtree(output_dir)


def write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    temp_path = path.with_suffix(".json.tmp")
    with temp_path.open("w", encoding="utf-8") as file:
        json.dump(manifest, file, ensure_ascii=False, indent=2, sort_keys=True)
        file.write("\n")
    temp_path.replace(path)


def part_files(output_dir: Path) -> list[Path]:
    return sorted(output_dir.glob("part-*.parquet"))


def parquet_row_count(path: Path) -> int:
    return int(pl.scan_parquet(str(path)).select(pl.len()).collect().item())


def scan_parts(output_dir: Path) -> pl.LazyFrame:
    files = part_files(output_dir)
    if not files:
        raise DatasetBuildError(f"No dataset part files found in {output_dir}")
    return pl.scan_parquet([str(file) for file in files])


def count_by(frame: pl.LazyFrame, column: str) -> dict[str, int]:
    if column not in frame.collect_schema().names():
        return {}
    rows = (
        frame.group_by(column)
        .agg(pl.len().alias("row_count"))
        .sort(column)
        .collect()
        .to_dicts()
    )
    return {str(row[column]): int(row["row_count"]) for row in rows}


def build_drop_counts(
    positions: dict[str, pl.LazyFrame],
    labels: dict[str, pl.LazyFrame],
) -> dict[str, int]:
    position_raw = count_rows(positions["raw"])
    position_valid_snapshot = count_rows(positions["valid_snapshot"])
    position_fresh = count_rows(positions["fresh"])
    position_prepared = count_rows(positions["prepared"])
    label_raw = count_rows(labels["raw"])
    label_filtered = count_rows(labels["filtered"])

    return {
        "position_raw": position_raw,
        "position_invalid_data_tm_dropped": position_raw - position_valid_snapshot,
        "position_stale_dropped": position_valid_snapshot - position_fresh,
        "position_missing_required_dropped": position_fresh - position_prepared,
        "position_prepared": position_prepared,
        "label_raw": label_raw,
        "label_filtered_dropped": label_raw - label_filtered,
        "label_prepared": label_filtered,
    }


def build_dataset(service_date: date, data_dir: Path, overwrite: bool) -> None:
    label_path = require_archive(data_dir, "bus_label", service_date)
    position_path = require_archive(data_dir, "bus_position", service_date)
    output_dir = partition_dir(data_dir, "dataset", service_date)
    manifest_path = output_dir / "manifest.json"

    if part_files(output_dir) and not overwrite:
        print(f"[skip] dataset {service_date}: {output_dir} already has part files")
        return

    if overwrite:
        remove_dataset_outputs(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    positions = position_frames(position_path)
    labels = label_frames(label_path)
    generated_at = datetime.now(timezone.utc).isoformat()

    drops = build_drop_counts(positions, labels)
    routes = route_ids(positions["prepared"], labels["filtered"])

    part_index = 0
    written_files: list[str] = []
    try:
        for route_id in routes:
            route_dataset = dataset_for_route(
                positions["prepared"], labels["filtered"], route_id, generated_at
            )
            route_frame = route_dataset.collect()
            if route_frame.is_empty():
                print(f"[route] {route_id}: rows=0")
                continue

            part_path = output_dir / f"part-{part_index:04d}.parquet"
            route_frame.write_parquet(part_path)
            written_files.append(part_path.name)
            print(f"[route] {route_id}: rows={route_frame.height} -> {part_path.name}")
            part_index += 1

        if not written_files:
            raise DatasetBuildError(f"No dataset rows generated for {service_date}")

        dataset = scan_parts(output_dir)
        row_count = count_rows(dataset)
        route_counts = count_by(dataset, "route_id")
        label_source_counts = count_by(dataset, "label_source")

        manifest = {
            "archive_version": DATASET_ARCHIVE_VERSION,
            "schema_version": SCHEMA_VERSION,
            "query_version": QUERY_VERSION,
            "service_date": service_date.isoformat(),
            "row_count": row_count,
            "route_counts": route_counts,
            "label_source_counts": label_source_counts,
            "drop_counts": drops,
            "generated_at": generated_at,
            "input_files": {
                "bus_label": str(label_path),
                "bus_position": str(position_path),
            },
            "part_files": written_files,
            "filters": [
                "position data_tm parses as %Y%m%d%H%M%S",
                "collected_at - snapshot_at between -1 minute and 2 minutes",
                "section_order is not null",
                "excluded_from_training = false",
                "label_arrival_at is not null",
                "api_estimated_arrival_at is not null",
                "snapshot_at + 1 minute < label_arrival_at <= snapshot_at + 40 minutes",
                "target_seq >= current_section_order",
                "label_target_seconds_to_arrival between 60 and 2400",
            ],
        }
        write_manifest(manifest_path, manifest)
        print(f"[done] dataset {service_date}: rows={row_count}, parts={len(written_files)}")
    except Exception:
        remove_dataset_outputs(output_dir)
        raise


def main() -> int:
    args = parse_args()
    try:
        service_date = parse_service_date(args.service_date)
        data_dir = load_data_dir()
        build_dataset(service_date, data_dir, args.overwrite)
    except (DatasetBuildError, OSError, pl.exceptions.PolarsError) as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
