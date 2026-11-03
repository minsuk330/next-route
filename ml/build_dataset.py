from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import polars as pl
from dotenv import load_dotenv

from realtime_features import (
    REALTIME_FEATURES,
    TRIP_INDEX_COLUMN,
    add_realtime_features,
    assign_trip_index,
)


SCHEMA_VERSION = 2
QUERY_VERSION = "v1"
DATASET_ARCHIVE_VERSION = "v1"

# Calendar feature boundaries (hour of day, KST snapshot). These constants are the
# single spec — the Java MlFeatureVectorBuilder must replicate them exactly to avoid
# train/serve skew. [start, end) half-open for commute windows.
COMMUTE_MORNING = (7, 9)
COMMUTE_EVENING = (18, 20)
LATE_NIGHT_START = 23  # hour >= 23
LATE_NIGHT_END = 5  # or hour < 5

# How far ahead of an arrival a snapshot may be and still become a training row.
# The trip-consistent join already caps every row at the vehicle's remaining trip,
# so this is the outer bound for the longest routes.
DEFAULT_MAX_HORIZON_MINUTES = 40
MIN_HORIZON_SECONDS = 60


class DatasetBuildError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build bus ML dataset parquet from archived label/position parquet."
    )
    parser.add_argument(
        "service_date",
        nargs="?",
        help="Service date in YYYY-MM-DD format. Omit when using --from/--to.",
    )
    parser.add_argument("--from", dest="from_date", help="Inclusive start date.")
    parser.add_argument("--to", dest="to_date", help="Inclusive end date.")
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Regenerate dataset even when parquet parts already exist.",
    )
    parser.add_argument(
        "--sample-fraction",
        type=float,
        default=1.0,
        help=(
            "Keep this fraction of each route's rows (uniform, seeded by service date). "
            "A 90-minute horizon on a busy day yields 30M+ rows (~1GB) while training "
            "samples a few million in total; thinning at build time keeps the cache small."
        ),
    )
    parser.add_argument(
        "--max-horizon-minutes",
        type=int,
        default=DEFAULT_MAX_HORIZON_MINUTES,
        help="Keep snapshots at most this many minutes before the arrival.",
    )
    return parser.parse_args()


def parse_service_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise DatasetBuildError(
            f"Invalid service date '{value}'. Use YYYY-MM-DD."
        ) from exc


def service_dates_from_args(args: argparse.Namespace) -> list[date]:
    if args.from_date or args.to_date:
        if args.service_date:
            raise DatasetBuildError("Use either service_date or --from/--to, not both.")
        if not args.from_date or not args.to_date:
            raise DatasetBuildError("--from and --to must be used together.")
        start = parse_service_date(args.from_date)
        end = parse_service_date(args.to_date)
        if end < start:
            raise DatasetBuildError("--to must be greater than or equal to --from.")
        days = (end - start).days
        return [start + timedelta(days=offset) for offset in range(days + 1)]

    if not args.service_date:
        raise DatasetBuildError("service_date is required unless --from/--to is used.")
    return [parse_service_date(args.service_date)]


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


# Raw ASOS observations carried by the weather sidecar (ml/weather_fetch.py). The
# derived flags below are computed here so a threshold change never needs a refetch.
WEATHER_RAW_COLUMNS = [
    "temperature_c",
    "precipitation_mm",
    "humidity_pct",
    "wind_speed_mps",
    "snow_depth_cm",
]
WEATHER_DERIVED_COLUMNS = ["is_raining", "is_snowing", "weather_severity"]
WEATHER_COLUMNS = WEATHER_RAW_COLUMNS + WEATHER_DERIVED_COLUMNS


def weather_sidecar_path(data_dir: Path, service_date: date) -> Path:
    return (
        data_dir / "weather" / f"service_date={service_date.isoformat()}" / "data.parquet"
    )


def load_weather(data_dir: Path, service_date: date) -> pl.LazyFrame | None:
    """Scan the weather sidecar for a date. Missing sidecar returns None — the join
    is skipped and all weather features fall back to null (LightGBM NaN)."""
    path = weather_sidecar_path(data_dir, service_date)
    if not path.exists():
        print(f"[warn] weather sidecar missing: {path} — weather features will be null")
        return None
    return pl.scan_parquet(str(path))


def weather_derived_columns() -> list[pl.Expr]:
    """Deterministic snow/rain/severity from raw ASOS columns. This is the single
    spec the Java serving builder must replicate exactly. Snow takes precedence over
    rain (진눈깨비 → snow). Null inputs propagate to null derived values."""
    precip = pl.col("precipitation_mm")
    snow = pl.col("snow_depth_cm")
    wind = pl.col("wind_speed_mps")

    is_snowing = (
        pl.when(snow.is_null())
        .then(pl.lit(None, dtype=pl.Int8))
        .when(snow > 0)
        .then(pl.lit(1, dtype=pl.Int8))
        .otherwise(pl.lit(0, dtype=pl.Int8))
        .alias("is_snowing")
    )
    is_raining = (
        pl.when(precip.is_null())
        .then(pl.lit(None, dtype=pl.Int8))
        .when((precip > 0) & (snow.fill_null(0) == 0))
        .then(pl.lit(1, dtype=pl.Int8))
        .otherwise(pl.lit(0, dtype=pl.Int8))
        .alias("is_raining")
    )

    all_null = precip.is_null() & snow.is_null() & wind.is_null()
    p = precip.fill_null(0)
    s = snow.fill_null(0)
    w = wind.fill_null(0)
    weather_severity = (
        pl.when(all_null)
        .then(pl.lit(None, dtype=pl.Int8))
        .when((p >= 10) | (s >= 5) | (w >= 14))
        .then(pl.lit(3, dtype=pl.Int8))
        .when((p >= 3) | (s >= 1) | (w >= 10))
        .then(pl.lit(2, dtype=pl.Int8))
        .when((p >= 0.1) | (s > 0) | (w >= 7))
        .then(pl.lit(1, dtype=pl.Int8))
        .otherwise(pl.lit(0, dtype=pl.Int8))
        .alias("weather_severity")
    )
    return [is_raining, is_snowing, weather_severity]


def attach_weather(frame: pl.LazyFrame, weather: pl.LazyFrame | None) -> pl.LazyFrame:
    """Left-join the hourly weather sidecar on weather_hour, then derive flags.
    weather_hour = snapshot_at truncated to the hour (same KST base as serving)."""
    frame = frame.with_columns(
        pl.col("snapshot_at").dt.truncate("1h").alias("weather_hour")
    )
    if weather is not None:
        frame = frame.join(weather, on="weather_hour", how="left")
    else:
        frame = frame.with_columns(
            [pl.lit(None, dtype=pl.Float64).alias(col) for col in WEATHER_RAW_COLUMNS]
        )
    return frame.with_columns(weather_derived_columns())


def holiday_sidecar_path(data_dir: Path) -> Path:
    return data_dir / "calendar" / "holidays.parquet"


def load_holidays(data_dir: Path) -> list[date]:
    """Read the holiday sidecar (ml/holiday_fetch.py output). Missing sidecar
    returns an empty list — holiday-derived features fall back to null so a stale
    or absent calendar is visibly unknown rather than silently 'not a holiday'."""
    path = holiday_sidecar_path(data_dir)
    if not path.exists():
        print(f"[warn] holiday sidecar missing: {path} — holiday features will be null")
        return []
    frame = pl.read_parquet(str(path))
    if "holiday_date" not in frame.columns:
        return []
    return frame.get_column("holiday_date").to_list()


def calendar_columns(holidays: list[date]) -> list[pl.Expr]:
    """Calendar features derived from snapshot_at (KST). Boundaries are the module
    constants; the Java serving builder must match these exactly."""
    hour = pl.col("snapshot_at").dt.hour()
    commute = [
        ((hour >= COMMUTE_MORNING[0]) & (hour < COMMUTE_MORNING[1]))
        .cast(pl.Int8)
        .alias("is_commute_morning"),
        ((hour >= COMMUTE_EVENING[0]) & (hour < COMMUTE_EVENING[1]))
        .cast(pl.Int8)
        .alias("is_commute_evening"),
        ((hour >= LATE_NIGHT_START) | (hour < LATE_NIGHT_END))
        .cast(pl.Int8)
        .alias("is_late_night"),
    ]
    if holidays:
        snap_date = pl.col("snapshot_at").dt.date()
        holiday = [
            snap_date.is_in(holidays).cast(pl.Int8).alias("is_holiday"),
            snap_date.dt.offset_by("1d")
            .is_in(holidays)
            .cast(pl.Int8)
            .alias("is_pre_holiday"),
            snap_date.dt.offset_by("-1d")
            .is_in(holidays)
            .cast(pl.Int8)
            .alias("is_post_holiday"),
        ]
    else:
        holiday = [
            pl.lit(None, dtype=pl.Int8).alias("is_holiday"),
            pl.lit(None, dtype=pl.Int8).alias("is_pre_holiday"),
            pl.lit(None, dtype=pl.Int8).alias("is_post_holiday"),
        ]
    return holiday + commute


def require_archive(data_dir: Path, table: str, service_date: date) -> Path:
    data_path = partition_dir(data_dir, table, service_date) / "data.parquet"
    if not data_path.exists():
        raise DatasetBuildError(
            f"Missing archive: {data_path}. Run archive.py {service_date} first."
        )
    return data_path


def clean_identifier(name: str) -> pl.Expr:
    value = pl.col(name).cast(pl.Utf8).str.strip_chars()
    return (
        pl.when(value.is_not_null() & (value != ""))
        .then(value)
        .otherwise(pl.lit(None, dtype=pl.Utf8))
    )


def clean_stop_id(name: str) -> pl.Expr:
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
            clean_identifier("vehicle_id").alias("_vehicle_id_clean"),
            clean_identifier("plain_no").alias("_plain_no_clean"),
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
                clean_stop_id("next_stop_id").alias("next_stop_id_clean"),
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
    holidays: list[date],
    weather: pl.LazyFrame | None,
    max_horizon_minutes: int = DEFAULT_MAX_HORIZON_MINUTES,
) -> pl.LazyFrame:
    max_horizon_seconds = max_horizon_minutes * 60
    position_route = assign_trip_index(position.filter(pl.col("route_id") == route_id))
    label_route = assign_label_trip_index(
        label.filter(pl.col("route_id") == route_id), position_route
    )

    # Join on the trip as well as the vehicle. A circular route (most village buses)
    # completes a lap inside the 40-minute window, so a vehicle-only join also pairs a
    # snapshot with the *next* lap's arrival at the same stop — a target one lap
    # (~20 min) too long.
    joined = position_route.join(
        label_route,
        on=["route_id", "vehicle_identity", TRIP_INDEX_COLUMN],
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

    enriched = (
        with_targets.filter(
            (pl.col("label_arrival_at") > pl.col("snapshot_at") + pl.duration(minutes=1))
            & (
                pl.col("label_arrival_at")
                <= pl.col("snapshot_at") + pl.duration(minutes=max_horizon_minutes)
            )
            & (pl.col("target_seq") >= pl.col("current_section_order"))
            & (pl.col("label_target_seconds_to_arrival") >= MIN_HORIZON_SECONDS)
            & (pl.col("label_target_seconds_to_arrival") <= max_horizon_seconds)
        )
        .with_columns(
            [
                pl.col("snapshot_at").dt.hour().alias("hour_of_day"),
                # minute_of_day = minute-of-day (0–1439), matching Java
                # MlFeatureVectorBuilder (hour*60+minute). Was dt.minute() (0–59) → train/serve skew.
                # dt.hour() is Int8; cast before *60 to avoid Int8 overflow (8*60 wraps).
                (pl.col("snapshot_at").dt.hour().cast(pl.Int32) * 60
                 + pl.col("snapshot_at").dt.minute())
                .alias("minute_of_day"),
                pl.col("snapshot_at").dt.weekday().alias("day_of_week"),
                pl.col("snapshot_at")
                .dt.weekday()
                .is_in([6, 7])
                .alias("is_weekend"),
                *calendar_columns(holidays),
                pl.lit(SCHEMA_VERSION).alias("schema_version"),
                pl.lit(generated_at).alias("generated_at"),
            ]
        )
    )

    # Safety net: one (snapshot, target stop) pair keeps only the earliest arrival.
    first_arrival = enriched.sort(["label_arrival_at", "arrival_label_event_id"]).unique(
        subset=["position_raw_id", "target_seq"], keep="first", maintain_order=True
    )

    # rt_* columns are added by build_dataset() after collect (they need the route's
    # position history as a DataFrame), so this lazy plan stops at the base columns.
    return attach_weather(first_arrival, weather).select(base_dataset_columns())


def assign_label_trip_index(label: pl.LazyFrame, position: pl.LazyFrame) -> pl.LazyFrame:
    """Give each arrival the trip of the vehicle's last snapshot at or before it."""
    snapshots = position.select(
        "route_id",
        "vehicle_identity",
        pl.col("snapshot_at").alias("_trip_snapshot_at"),
        TRIP_INDEX_COLUMN,
    ).sort("_trip_snapshot_at")
    return (
        label.sort("label_arrival_at")
        .join_asof(
            snapshots,
            left_on="label_arrival_at",
            right_on="_trip_snapshot_at",
            by=["route_id", "vehicle_identity"],
            strategy="backward",
            check_sortedness=False,
        )
        .drop("_trip_snapshot_at")
    )


def dataset_columns() -> list[str]:
    columns = base_dataset_columns()
    index = columns.index("api_target_seconds_to_arrival")
    return [*columns[:index], *REALTIME_FEATURES, *columns[index:]]


def base_dataset_columns() -> list[str]:
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
        "is_holiday",
        "is_pre_holiday",
        "is_post_holiday",
        "is_commute_morning",
        "is_commute_evening",
        "is_late_night",
        "temperature_c",
        "precipitation_mm",
        "humidity_pct",
        "wind_speed_mps",
        "snow_depth_cm",
        "is_raining",
        "is_snowing",
        "weather_severity",
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


def build_dataset(
    service_date: date,
    data_dir: Path,
    overwrite: bool,
    max_horizon_minutes: int = DEFAULT_MAX_HORIZON_MINUTES,
    sample_fraction: float = 1.0,
) -> None:
    if not 0 < sample_fraction <= 1:
        raise DatasetBuildError(f"--sample-fraction must be in (0, 1], got {sample_fraction}")
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
    holidays = load_holidays(data_dir)
    weather = load_weather(data_dir, service_date)

    drops = build_drop_counts(positions, labels)
    routes = route_ids(positions["prepared"], labels["filtered"])

    part_index = 0
    written_files: list[str] = []
    try:
        for route_id in routes:
            route_dataset = dataset_for_route(
                positions["prepared"],
                labels["filtered"],
                route_id,
                generated_at,
                holidays,
                weather,
                max_horizon_minutes,
            )
            route_frame = route_dataset.collect(engine="streaming")
            if route_frame.is_empty():
                print(f"[route] {route_id}: rows=0")
                continue
            # Same-route position history (positions only, no labels) -> rt_* columns.
            # Serving recomputes these from the Java app's rolling history with the
            # same function, so the dataset carries exactly what the model will see.
            history = (
                positions["prepared"]
                .filter(pl.col("route_id") == route_id)
                .select("route_id", "vehicle_identity", "snapshot_at", "current_section_order")
                .collect(engine="streaming")
            )
            route_frame = add_realtime_features(route_frame, history).select(dataset_columns())
            if sample_fraction < 1.0:
                route_frame = route_frame.sample(
                    fraction=sample_fraction, seed=service_date.toordinal(), shuffle=True
                )

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
        weather_present_rows = int(
            dataset.select(pl.col("temperature_c").is_not_null().sum()).collect().item()
        )

        manifest = {
            "archive_version": DATASET_ARCHIVE_VERSION,
            "schema_version": SCHEMA_VERSION,
            "query_version": QUERY_VERSION,
            "max_horizon_minutes": max_horizon_minutes,
            "sample_fraction": sample_fraction,
            "service_date": service_date.isoformat(),
            "row_count": row_count,
            "holiday_sidecar_dates": len(holidays),
            "weather_sidecar_present": weather is not None,
            "weather_coverage_rows": weather_present_rows,
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
                f"snapshot_at + 1 minute < label_arrival_at <= snapshot_at + {max_horizon_minutes} minutes",
                "target_seq >= current_section_order",
                "snapshot and arrival in the same vehicle trip",
                f"label_target_seconds_to_arrival between {MIN_HORIZON_SECONDS} and {max_horizon_minutes * 60}",
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
        service_dates = service_dates_from_args(args)
        data_dir = load_data_dir()
        for service_date in service_dates:
            build_dataset(
                service_date, data_dir, args.overwrite, args.max_horizon_minutes, args.sample_fraction
            )
    except (DatasetBuildError, OSError, pl.exceptions.PolarsError) as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
