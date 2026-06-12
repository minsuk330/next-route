from __future__ import annotations

import math
import os
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Literal

import polars as pl
from dotenv import load_dotenv


TargetName = Literal["api", "label", "corrected"]

TARGET_COLUMNS: dict[str, str] = {
    "api": "api_target_seconds_to_arrival",
    "label": "label_target_seconds_to_arrival",
    "corrected": "corrected_target_seconds_to_arrival",
}

HORIZON_BUCKETS = ("1-5m", "5-10m", "10-20m", "20-40m")


@dataclass(frozen=True)
class DatasetSplit:
    train: pl.DataFrame
    test: pl.DataFrame
    target_column: str
    target_name: str
    dataset_path: str
    service_dates: list[str]
    split_policy: dict[str, object]
    sampling_policy: dict[str, object]


def load_data_dir() -> Path:
    ml_dir = Path(__file__).resolve().parent
    load_dotenv(ml_dir / ".env")

    data_dir_value = os.getenv("DATA_DIR", "./data")
    data_dir = Path(data_dir_value).expanduser()
    if not data_dir.is_absolute():
        data_dir = ml_dir / data_dir
    return data_dir


def dataset_dir(data_dir: Path, service_date: date) -> Path:
    return data_dir / "dataset" / f"service_date={service_date.isoformat()}"


def dataset_part_files(data_dir: Path, service_date: date) -> list[Path]:
    output_dir = dataset_dir(data_dir, service_date)
    files = sorted(output_dir.glob("part-*.parquet"))
    if not files:
        raise FileNotFoundError(
            f"No dataset parts found in {output_dir}. Run build_dataset.py first."
        )
    return files


def load_dataset(data_dir: Path, service_dates: Sequence[date]) -> pl.DataFrame:
    files = [
        file
        for service_date in service_dates
        for file in dataset_part_files(data_dir, service_date)
    ]
    return pl.scan_parquet([str(file) for file in files]).collect()


def add_horizon_bucket(frame: pl.DataFrame, target_column: str) -> pl.DataFrame:
    return frame.with_columns(
        [
            pl.when(pl.col(target_column) < 300)
            .then(pl.lit("1-5m"))
            .when(pl.col(target_column) < 600)
            .then(pl.lit("5-10m"))
            .when(pl.col(target_column) < 1200)
            .then(pl.lit("10-20m"))
            .otherwise(pl.lit("20-40m"))
            .alias("horizon_bucket")
        ]
    )


def filter_for_target(frame: pl.DataFrame, target_name: TargetName) -> tuple[pl.DataFrame, str]:
    target_column = TARGET_COLUMNS[target_name]
    filtered = frame.filter(
        pl.col(target_column).is_not_null()
        & (pl.col(target_column) >= 60)
        & (pl.col(target_column) <= 2400)
    )
    return add_horizon_bucket(filtered, target_column), target_column


def parse_test_from(value: str) -> time:
    try:
        hour, minute = value.split(":", 1)
        return time(hour=int(hour), minute=int(minute))
    except ValueError as exc:
        raise ValueError(f"Invalid --test-from '{value}'. Use HH:MM.") from exc


def cutoff_datetime(service_date: date, test_from: str) -> datetime:
    cutoff_time = parse_test_from(test_from)
    cutoff_date = service_date + timedelta(days=1) if cutoff_time.hour < 4 else service_date
    return datetime.combine(cutoff_date, cutoff_time)


def split_by_time(
    frame: pl.DataFrame,
    service_date: date,
    test_from: str,
    test_dates: set[date] | None = None,
) -> tuple[pl.DataFrame, pl.DataFrame, dict[str, object]]:
    if test_dates:
        split_frame = frame.with_columns(pl.col("service_date").cast(pl.Date))
        test = split_frame.filter(pl.col("service_date").is_in(sorted(test_dates)))
        train = split_frame.filter(~pl.col("service_date").is_in(sorted(test_dates)))
        policy = {
            "type": "service_date",
            "test_dates": [item.isoformat() for item in sorted(test_dates)],
        }
    else:
        cutoff = cutoff_datetime(service_date, test_from)
        boundary = frame.filter(
            (pl.col("snapshot_at") < cutoff)
            & (pl.col("label_arrival_at") > cutoff)
        )
        train = frame.filter(
            (pl.col("snapshot_at") < cutoff)
            & (pl.col("label_arrival_at") <= cutoff)
        )
        test = frame.filter(pl.col("snapshot_at") >= cutoff)
        policy = {
            "type": "snapshot_at_cutoff",
            "test_from": test_from,
            "cutoff": cutoff.isoformat(),
            "boundary_dropped": boundary.height,
        }

    if train.is_empty() or test.is_empty():
        raise ValueError(
            f"Split produced empty train/test: train={train.height}, test={test.height}."
        )
    return train, test, policy


def sample_bucket_route(
    frame: pl.DataFrame,
    n_rows: int,
    seed: int,
) -> pl.DataFrame:
    if frame.height <= n_rows:
        return frame
    if frame.is_empty() or n_rows <= 0:
        return frame.head(0)

    per_bucket = max(1, math.ceil(n_rows / len(HORIZON_BUCKETS)))
    sampled_parts: list[pl.DataFrame] = []
    for bucket in HORIZON_BUCKETS:
        bucket_frame = frame.filter(pl.col("horizon_bucket") == bucket)
        if bucket_frame.is_empty():
            continue
        routes = bucket_frame.select("route_id").unique().to_series().to_list()
        per_route = max(1, math.ceil(per_bucket / max(1, len(routes))))
        route_parts: list[pl.DataFrame] = []
        for route in routes:
            route_frame = bucket_frame.filter(pl.col("route_id") == route)
            take = min(route_frame.height, per_route)
            route_parts.append(route_frame.sample(n=take, seed=seed, shuffle=True))
        bucket_sample = pl.concat(route_parts) if route_parts else bucket_frame.head(0)
        if bucket_sample.height > per_bucket:
            bucket_sample = bucket_sample.sample(n=per_bucket, seed=seed, shuffle=True)
        sampled_parts.append(bucket_sample)

    sampled = pl.concat(sampled_parts) if sampled_parts else frame.head(0)
    if sampled.height > n_rows:
        sampled = sampled.sample(n=n_rows, seed=seed, shuffle=True)
    elif sampled.height < n_rows:
        remaining = frame.join(
            sampled.select(["position_raw_id", "arrival_label_event_id"]),
            on=["position_raw_id", "arrival_label_event_id"],
            how="anti",
        )
        if not remaining.is_empty():
            fill = remaining.sample(
                n=min(n_rows - sampled.height, remaining.height),
                seed=seed,
                shuffle=True,
            )
            sampled = pl.concat([sampled, fill])
    return sampled


def parse_test_dates(values: str | None) -> set[date] | None:
    if not values:
        return None
    return {date.fromisoformat(value.strip()) for value in values.split(",") if value.strip()}


def normalize_service_dates(service_dates: date | Sequence[date]) -> list[date]:
    if isinstance(service_dates, date):
        return [service_dates]
    normalized = sorted(set(service_dates))
    if not normalized:
        raise ValueError("At least one service_date is required.")
    return normalized


def create_split(
    service_date: date | Sequence[date],
    target_name: TargetName,
    sample_rows: int | None = 2_000_000,
    test_from: str = "21:00",
    test_dates: str | None = None,
    seed: int = 42,
    data_dir: Path | None = None,
) -> DatasetSplit:
    data_dir = data_dir or load_data_dir()
    service_dates = normalize_service_dates(service_date)
    parsed_test_dates = parse_test_dates(test_dates)
    if len(service_dates) > 1 and not parsed_test_dates:
        raise ValueError("multi-date split requires --test-dates")

    dataset = load_dataset(data_dir, service_dates)
    filtered, target_column = filter_for_target(dataset, target_name)

    sampling_policy: dict[str, object] = {
        "sample_rows": sample_rows,
        "seed": seed,
        "strategy": "horizon_bucket_route_cap",
        "input_rows": filtered.height,
    }
    if sample_rows is not None and sample_rows > 0 and filtered.height > sample_rows:
        filtered = sample_bucket_route(filtered, sample_rows, seed)
    sampling_policy["output_rows"] = filtered.height

    train, test, split_policy = split_by_time(
        filtered,
        service_dates[0],
        test_from,
        parsed_test_dates,
    )

    return DatasetSplit(
        train=train,
        test=test,
        target_column=target_column,
        target_name=target_name,
        dataset_path=",".join(str(dataset_dir(data_dir, item)) for item in service_dates),
        service_dates=[item.isoformat() for item in service_dates],
        split_policy=split_policy,
        sampling_policy=sampling_policy,
    )
