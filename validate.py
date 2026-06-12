from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import polars as pl
from dotenv import load_dotenv


HORIZON_BUCKETS = ("1-5m", "5-10m", "10-20m", "20-40m")
REQUIRED_FEATURES = [
    "snapshot_at",
    "route_id",
    "vehicle_identity",
    "current_section_id",
    "current_section_order",
    "current_section_distance",
    "current_full_section_distance",
    "section_progress",
    "target_stop_id",
    "target_seq",
    "remaining_stop_count",
    "label_arrival_at",
    "api_estimated_arrival_at",
    "label_target_seconds_to_arrival",
    "gps_x",
    "gps_y",
]
TARGET_COLUMNS = [
    "api_target_seconds_to_arrival",
    "corrected_target_seconds_to_arrival",
    "label_target_seconds_to_arrival",
]


class ValidationError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate bus ML archives and generated dataset parquet."
    )
    parser.add_argument(
        "service_date",
        nargs="?",
        help="Service date in YYYY-MM-DD format. Omit when using --from/--to.",
    )
    parser.add_argument("--from", dest="from_date", help="Inclusive start date.")
    parser.add_argument("--to", dest="to_date", help="Inclusive end date.")
    return parser.parse_args()


def parse_service_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise ValidationError(f"Invalid service date '{value}'. Use YYYY-MM-DD.") from exc


def service_dates_from_args(args: argparse.Namespace) -> list[date]:
    if args.from_date or args.to_date:
        if args.service_date:
            raise ValidationError("Use either service_date or --from/--to, not both.")
        if not args.from_date or not args.to_date:
            raise ValidationError("--from and --to must be used together.")
        start = parse_service_date(args.from_date)
        end = parse_service_date(args.to_date)
        if end < start:
            raise ValidationError("--to must be greater than or equal to --from.")
        days = (end - start).days
        return [start + timedelta(days=offset) for offset in range(days + 1)]

    if not args.service_date:
        raise ValidationError("service_date is required unless --from/--to is used.")
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


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def write_json(path: Path, payload: dict[str, Any]) -> None:
    temp_path = path.with_suffix(".json.tmp")
    with temp_path.open("w", encoding="utf-8") as file:
        json.dump(payload, file, ensure_ascii=False, indent=2, sort_keys=True)
        file.write("\n")
    temp_path.replace(path)


def count_rows(frame: pl.LazyFrame) -> int:
    return int(frame.select(pl.len()).collect().item())


def part_files(output_dir: Path) -> list[Path]:
    return sorted(output_dir.glob("part-*.parquet"))


def scan_parts(output_dir: Path) -> pl.LazyFrame:
    files = part_files(output_dir)
    if not files:
        raise ValidationError(f"No dataset part files found in {output_dir}")
    return pl.scan_parquet([str(file) for file in files])


def count_by(frame: pl.LazyFrame, column: str) -> dict[str, int]:
    rows = (
        frame.group_by(column)
        .agg(pl.len().alias("row_count"))
        .sort(column)
        .collect()
        .to_dicts()
    )
    return {str(row[column]): int(row["row_count"]) for row in rows}


def validate_archive_table(data_dir: Path, table: str, service_date: date) -> dict[str, Any]:
    output_dir = partition_dir(data_dir, table, service_date)
    data_path = output_dir / "data.parquet"
    manifest_path = output_dir / "manifest.json"

    errors: list[str] = []
    if not manifest_path.exists():
        errors.append(f"missing manifest: {manifest_path}")
    if not data_path.exists():
        errors.append(f"missing parquet: {data_path}")
        return {"table": table, "errors": errors, "passed": False}

    frame = pl.scan_parquet(str(data_path))
    row_count = count_rows(frame)
    manifest: dict[str, Any] = {}
    manifest_count = None
    if manifest_path.exists():
        manifest = load_json(manifest_path)
        manifest_count = manifest.get("row_count")
        if manifest_count != row_count:
            errors.append(
                f"manifest row_count mismatch for {table}: manifest={manifest_count}, parquet={row_count}"
            )

    return {
        "table": table,
        "row_count": row_count,
        "manifest_row_count": manifest_count,
        "quality_counts": manifest.get("quality_counts", {}),
        "errors": errors,
        "passed": not errors,
    }


def horizon_counts(dataset: pl.LazyFrame) -> dict[str, int]:
    bucketed = dataset.with_columns(
        [
            pl.when(pl.col("label_target_seconds_to_arrival") < 300)
            .then(pl.lit("1-5m"))
            .when(pl.col("label_target_seconds_to_arrival") < 600)
            .then(pl.lit("5-10m"))
            .when(pl.col("label_target_seconds_to_arrival") < 1200)
            .then(pl.lit("10-20m"))
            .otherwise(pl.lit("20-40m"))
            .alias("horizon_bucket")
        ]
    )
    counts = count_by(bucketed, "horizon_bucket")
    return {bucket: counts.get(bucket, 0) for bucket in HORIZON_BUCKETS}


def target_quality(dataset: pl.LazyFrame) -> dict[str, dict[str, int]]:
    aggregations = []
    for column in TARGET_COLUMNS:
        aggregations.extend(
            [
                pl.col(column).is_null().sum().alias(f"{column}__null"),
                (
                    pl.col(column).is_not_null() & (pl.col(column) <= 0)
                ).sum().alias(f"{column}__le_zero"),
                (
                    pl.col(column).is_not_null() & (pl.col(column) > 2400)
                ).sum().alias(f"{column}__over_2400"),
            ]
        )
    row = dataset.select(aggregations).collect().to_dicts()[0]

    result: dict[str, dict[str, int]] = {}
    for column in TARGET_COLUMNS:
        result[column] = {
            "null": int(row[f"{column}__null"]),
            "le_zero": int(row[f"{column}__le_zero"]),
            "over_2400": int(row[f"{column}__over_2400"]),
        }
    return result


def feature_nulls(dataset: pl.LazyFrame, row_count: int) -> dict[str, dict[str, float]]:
    schema_names = set(dataset.collect_schema().names())
    aggregations = []
    for column in REQUIRED_FEATURES:
        if column in schema_names:
            aggregations.append(pl.col(column).is_null().sum().alias(column))
    row = dataset.select(aggregations).collect().to_dicts()[0] if aggregations else {}

    result: dict[str, dict[str, float]] = {}
    for column in REQUIRED_FEATURES:
        null_count = int(row.get(column, row_count))
        result[column] = {
            "null_count": null_count,
            "null_rate": null_count / row_count if row_count else 1.0,
        }
    return result


def duplicate_pair_count(dataset: pl.LazyFrame) -> int:
    duplicates = (
        dataset.group_by(["position_raw_id", "arrival_label_event_id"])
        .agg(pl.len().alias("row_count"))
        .filter(pl.col("row_count") > 1)
    )
    return count_rows(duplicates)


def validate_dataset(data_dir: Path, service_date: date) -> dict[str, Any]:
    output_dir = partition_dir(data_dir, "dataset", service_date)
    manifest_path = output_dir / "manifest.json"
    errors: list[str] = []

    files = part_files(output_dir)
    if not manifest_path.exists() and not files:
        return {
            "row_count": 0,
            "manifest_row_count": None,
            "route_counts": {},
            "label_source_counts": {},
            "horizon_counts": {},
            "target_quality": {},
            "feature_nulls": {},
            "remaining_stop_count_negative": 0,
            "snapshot_at_gte_label_arrival_at": 0,
            "duplicate_position_label_pairs": 0,
            "manifest": {},
            "errors": [],
            "warnings": ["dataset cache not found; archive-only validation"],
            "skipped": True,
            "passed": True,
        }

    if not manifest_path.exists():
        errors.append(f"missing manifest: {manifest_path}")
    dataset = scan_parts(output_dir)
    row_count = count_rows(dataset)

    manifest: dict[str, Any] = {}
    manifest_count = None
    if manifest_path.exists():
        manifest = load_json(manifest_path)
        manifest_count = manifest.get("row_count")
        if manifest_count != row_count:
            errors.append(
                f"dataset manifest row_count mismatch: manifest={manifest_count}, parquet={row_count}"
            )

    route_counts = count_by(dataset, "route_id")
    label_source_counts = count_by(dataset, "label_source")
    horizons = horizon_counts(dataset)
    targets = target_quality(dataset)
    nulls = feature_nulls(dataset, row_count)
    remaining_negative = int(
        dataset.select((pl.col("remaining_stop_count") < 0).sum()).collect().item()
    )
    snapshot_after_label = int(
        dataset.select((pl.col("snapshot_at") >= pl.col("label_arrival_at")).sum())
        .collect()
        .item()
    )
    duplicate_pairs = duplicate_pair_count(dataset)

    warnings: list[str] = []
    if targets["api_target_seconds_to_arrival"]["le_zero"] != 0:
        warnings.append("api_target_seconds_to_arrival <= 0 rows found")
    if targets["label_target_seconds_to_arrival"]["le_zero"] != 0:
        errors.append("label_target_seconds_to_arrival <= 0 rows found")
    if remaining_negative != 0:
        errors.append("remaining_stop_count < 0 rows found")
    if snapshot_after_label != 0:
        errors.append("snapshot_at >= label_arrival_at rows found")
    if duplicate_pairs != 0:
        errors.append("(position_raw_id, arrival_label_event_id) duplicate rows found")

    high_null_features = [
        column
        for column, stats in nulls.items()
        if stats["null_rate"] > 0.01
    ]
    if high_null_features:
        errors.append(f"feature null rate > 1%: {', '.join(high_null_features)}")

    missing_horizons = [bucket for bucket, count in horizons.items() if count == 0]
    if missing_horizons:
        errors.append(f"empty horizon buckets: {', '.join(missing_horizons)}")
    if not label_source_counts:
        errors.append("label_source counts are empty")

    return {
        "row_count": row_count,
        "manifest_row_count": manifest_count,
        "route_counts": route_counts,
        "label_source_counts": label_source_counts,
        "horizon_counts": horizons,
        "target_quality": targets,
        "feature_nulls": nulls,
        "remaining_stop_count_negative": remaining_negative,
        "snapshot_at_gte_label_arrival_at": snapshot_after_label,
        "duplicate_position_label_pairs": duplicate_pairs,
        "manifest": manifest,
        "errors": errors,
        "warnings": warnings,
        "skipped": False,
        "passed": not errors,
    }


def print_count_section(title: str, counts: dict[str, int], limit: int | None = None) -> None:
    print(f"\n{title}")
    print("-" * len(title))
    items = sorted(counts.items(), key=lambda item: item[0])
    if limit is not None:
        items = items[:limit]
    for key, value in items:
        print(f"{key:>24}  {value:>12}")


def print_report(report: dict[str, Any]) -> None:
    print(f"Validation for service_date={report['service_date']}")
    print(f"passed={report['passed']}")

    print("\nArchive")
    print("-------")
    for item in report["archive"].values():
        print(
            f"{item['table']}: rows={item.get('row_count')}, "
            f"manifest_rows={item.get('manifest_row_count')}, passed={item['passed']}"
        )
        for error in item.get("errors", []):
            print(f"  - {error}")

    dataset = report["dataset"]
    print("\nDataset")
    print("-------")
    if dataset.get("skipped"):
        print("skipped=True (dataset cache not found; archive-only validation)")
        for warning in dataset.get("warnings", []):
            print(f"  - {warning}")
        if report["errors"]:
            print("\nErrors")
            print("------")
            for error in report["errors"]:
                print(f"- {error}")
        return

    print(
        f"rows={dataset['row_count']}, "
        f"manifest_rows={dataset.get('manifest_row_count')}, passed={dataset['passed']}"
    )
    print_count_section("Label source counts", dataset["label_source_counts"])
    print_count_section("Horizon counts", dataset["horizon_counts"])
    print_count_section("Route counts", dataset["route_counts"], limit=20)

    print("\nTarget quality")
    print("--------------")
    for column, stats in dataset["target_quality"].items():
        print(
            f"{column}: null={stats['null']}, "
            f"le_zero={stats['le_zero']}, over_2400={stats['over_2400']}"
        )

    print("\nFeature nulls")
    print("-------------")
    for column, stats in dataset["feature_nulls"].items():
        print(
            f"{column:>36}  null={stats['null_count']:>10}  "
            f"rate={stats['null_rate']:.4f}"
        )

    if report["errors"]:
        print("\nErrors")
        print("------")
        for error in report["errors"]:
            print(f"- {error}")
    if report.get("warnings"):
        print("\nWarnings")
        print("--------")
        for warning in report["warnings"]:
            print(f"- {warning}")


def validate(service_date: date, data_dir: Path) -> dict[str, Any]:
    archive_report = {
        "bus_label": validate_archive_table(data_dir, "bus_label", service_date),
        "bus_position": validate_archive_table(data_dir, "bus_position", service_date),
        "bus_candidate": validate_archive_table(data_dir, "bus_candidate", service_date),
    }
    dataset_report = validate_dataset(data_dir, service_date)

    errors: list[str] = []
    warnings: list[str] = []
    for item in archive_report.values():
        errors.extend(item.get("errors", []))
    errors.extend(dataset_report.get("errors", []))
    warnings.extend(dataset_report.get("warnings", []))

    report = {
        "service_date": service_date.isoformat(),
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "archive": archive_report,
        "dataset": dataset_report,
        "errors": errors,
        "warnings": warnings,
        "passed": not errors,
    }

    validation_root = (
        partition_dir(data_dir, "dataset", service_date)
        if not dataset_report.get("skipped")
        else partition_dir(data_dir, "bus_label", service_date)
    )
    validation_root.mkdir(parents=True, exist_ok=True)
    validation_path = validation_root / "validation.json"
    write_json(validation_path, report)
    return report


def main() -> int:
    args = parse_args()
    try:
        service_dates = service_dates_from_args(args)
        data_dir = load_data_dir()
        passed = True
        for service_date in service_dates:
            report = validate(service_date, data_dir)
            print_report(report)
            passed = passed and report["passed"]
        return 0 if passed else 1
    except (ValidationError, OSError, pl.exceptions.PolarsError) as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
