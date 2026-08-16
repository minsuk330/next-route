from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote

import polars as pl
from dotenv import load_dotenv


SCHEMA_VERSION = 1
QUERY_VERSION = "v1"


class ArchiveError(RuntimeError):
    pass


@dataclass(frozen=True)
class ArchiveJob:
    archive_name: str
    source_table: str
    select_query: str
    count_query: str
    filters: list[str]
    quality_fn: Callable[[pl.DataFrame], dict[str, Any]]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Archive bus ML source tables to service_date partitioned parquet."
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
        help="Regenerate archives even when data.parquet already exists.",
    )
    return parser.parse_args()


def parse_service_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise ArchiveError(f"Invalid service date '{value}'. Use YYYY-MM-DD.") from exc


def service_dates_from_args(args: argparse.Namespace) -> list[date]:
    if args.from_date or args.to_date:
        if args.service_date:
            raise ArchiveError("Use either service_date or --from/--to, not both.")
        if not args.from_date or not args.to_date:
            raise ArchiveError("--from and --to must be used together.")
        start = parse_service_date(args.from_date)
        end = parse_service_date(args.to_date)
        if end < start:
            raise ArchiveError("--to must be greater than or equal to --from.")
        days = (end - start).days
        return [start + timedelta(days=offset) for offset in range(days + 1)]

    if not args.service_date:
        raise ArchiveError("service_date is required unless --from/--to is used.")
    return [parse_service_date(args.service_date)]


def load_config() -> tuple[str, Path]:
    ml_dir = Path(__file__).resolve().parent
    load_dotenv(ml_dir / ".env")

    db_url = db_url_from_env()
    if not db_url:
        raise ArchiveError(
            "DB_URL or POSTGRES_HOST/PORT/DB/USER/PASSWORD is required."
        )

    data_dir_value = os.getenv("DATA_DIR", "./data")
    data_dir = Path(data_dir_value).expanduser()
    if not data_dir.is_absolute():
        data_dir = ml_dir / data_dir

    return db_url, data_dir


def db_url_from_env() -> str | None:
    if db_url := os.getenv("DB_URL"):
        return db_url

    host = os.getenv("POSTGRES_HOST")
    port = os.getenv("POSTGRES_PORT", "5432")
    database = os.getenv("POSTGRES_DB")
    user = os.getenv("POSTGRES_USER")
    password = os.getenv("POSTGRES_PASSWORD")
    if not all([host, port, database, user, password]):
        return None

    return (
        f"postgresql://{quote(user or '', safe='')}:"
        f"{quote(password or '', safe='')}@{host}:{port}/"
        f"{quote(database or '', safe='')}"
    )


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def read_database(query: str, db_url: str) -> pl.DataFrame:
    return pl.read_database_uri(query, db_url)


def read_count(query: str, db_url: str) -> int:
    result = read_database(query, db_url)
    if result.height != 1 or result.width != 1:
        raise ArchiveError(f"Count query returned unexpected shape: {result.shape}")
    return int(result.item(0, 0))


def int_value(value: Any) -> int:
    if value is None:
        return 0
    return int(value)


def null_count(df: pl.DataFrame, column: str) -> int:
    if column not in df.columns:
        return 0
    return int_value(df.select(pl.col(column).is_null().sum()).item())


def label_source_counts(df: pl.DataFrame) -> dict[str, int]:
    if "label_source" not in df.columns or df.is_empty():
        return {}
    rows = (
        df.group_by("label_source")
        .agg(pl.len().alias("row_count"))
        .sort("label_source")
        .to_dicts()
    )
    return {str(row["label_source"]): int(row["row_count"]) for row in rows}


def duplicate_arrival_raw_id_count(df: pl.DataFrame) -> int:
    if "arrival_raw_id" not in df.columns or df.is_empty():
        return 0
    duplicates = (
        df.filter(pl.col("arrival_raw_id").is_not_null())
        .group_by("arrival_raw_id")
        .agg(pl.len().alias("row_count"))
        .filter(pl.col("row_count") > 1)
    )
    return duplicates.height


def label_quality_counts(df: pl.DataFrame) -> dict[str, Any]:
    return {
        "label_arrival_at_null": null_count(df, "label_arrival_at"),
        "api_estimated_arrival_at_null": null_count(df, "api_estimated_arrival_at"),
        "label_source_counts": label_source_counts(df),
        "arrival_raw_id_duplicate_count": duplicate_arrival_raw_id_count(df),
    }


def invalid_num14_count(df: pl.DataFrame, column: str) -> int:
    if column not in df.columns or df.is_empty():
        return 0
    is_num14 = pl.col(column).cast(pl.Utf8).str.contains(r"^\d{14}$").fill_null(False)
    return df.filter(is_num14.not_()).height


def gps_null_count(df: pl.DataFrame) -> int:
    missing = [name for name in ("gps_x", "gps_y") if name not in df.columns]
    if missing or df.is_empty():
        return 0
    return df.filter(
        pl.any_horizontal(pl.col("gps_x").is_null(), pl.col("gps_y").is_null())
    ).height


def position_quality_counts(df: pl.DataFrame) -> dict[str, Any]:
    return {
        "data_tm_num14_invalid": invalid_num14_count(df, "data_tm"),
        "section_order_null": null_count(df, "section_order"),
        "section_id_null": null_count(df, "section_id"),
        "gps_null": gps_null_count(df),
    }


def value_counts(df: pl.DataFrame, column: str) -> dict[str, int]:
    if column not in df.columns or df.is_empty():
        return {}
    rows = (
        df.group_by(column)
        .agg(pl.len().alias("row_count"))
        .sort(column)
        .to_dicts()
    )
    return {str(row[column]): int(row["row_count"]) for row in rows}


def candidate_quality_counts(df: pl.DataFrame) -> dict[str, Any]:
    return {
        "lifecycle_id_null": null_count(df, "lifecycle_id"),
        "arrival_order_counts": value_counts(df, "arrival_order"),
        "data_timestamp_null": null_count(df, "data_timestamp"),
    }


def build_jobs(service_date: date) -> list[ArchiveJob]:
    day = service_date.isoformat()
    next_day = (service_date + timedelta(days=1)).isoformat()
    day_sql = sql_string(day)
    start_sql = sql_string(f"{day} 04:00:00")
    end_sql = sql_string(f"{next_day} 04:00:00")

    label_filter = f"service_date = date {day_sql}"
    position_filter = (
        f"collected_at >= timestamp {start_sql} "
        f"and collected_at < timestamp {end_sql} "
        "and is_run_yn = '1'"
    )
    candidate_filter = (
        f"finalized_at >= timestamp {start_sql} "
        f"and finalized_at < timestamp {end_sql}"
    )

    label_select = f"""
        select *
        from bus_arrival_label_event
        where {label_filter}
    """
    label_count = f"""
        select count(*)
        from bus_arrival_label_event
        where {label_filter}
    """

    position_columns = """
        id,
        route_id,
        vehicle_id,
        plain_no,
        section_id,
        section_order,
        section_distance,
        full_section_distance,
        stop_flag,
        next_stop_id,
        next_stop_time,
        last_stop_time,
        congestion,
        gps_x,
        gps_y,
        data_tm,
        collected_at
    """
    position_select = f"""
        select {position_columns}
        from bus_position_raw
        where {position_filter}
    """
    position_count = f"""
        select count(*)
        from bus_position_raw
        where {position_filter}
    """
    candidate_select = f"""
        select *
        from bus_arrival_candidate_raw
        where {candidate_filter}
    """
    candidate_count = f"""
        select count(*)
        from bus_arrival_candidate_raw
        where {candidate_filter}
    """

    return [
        ArchiveJob(
            archive_name="bus_label",
            source_table="bus_arrival_label_event",
            select_query=label_select,
            count_query=label_count,
            filters=[label_filter],
            quality_fn=label_quality_counts,
        ),
        ArchiveJob(
            archive_name="bus_position",
            source_table="bus_position_raw",
            select_query=position_select,
            count_query=position_count,
            filters=[
                "collected_at >= service_date 04:00",
                "collected_at < service_date + 1 day 04:00",
                "is_run_yn = '1'",
            ],
            quality_fn=position_quality_counts,
        ),
        ArchiveJob(
            archive_name="bus_candidate",
            source_table="bus_arrival_candidate_raw",
            select_query=candidate_select,
            count_query=candidate_count,
            filters=[
                "finalized_at >= service_date 04:00",
                "finalized_at < service_date + 1 day 04:00",
            ],
            quality_fn=candidate_quality_counts,
        ),
    ]


def archive_dir(data_dir: Path, job: ArchiveJob, service_date: date) -> Path:
    return data_dir / job.archive_name / f"service_date={service_date.isoformat()}"


def remove_outputs(output_dir: Path) -> None:
    for name in ("data.parquet", "manifest.json"):
        path = output_dir / name
        if path.exists():
            path.unlink()


def parquet_row_count(path: Path) -> int:
    return int(pl.scan_parquet(str(path)).select(pl.len()).collect().item())


def write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    temp_path = path.with_suffix(".json.tmp")
    with temp_path.open("w", encoding="utf-8") as file:
        json.dump(manifest, file, ensure_ascii=False, indent=2, sort_keys=True)
        file.write("\n")
    temp_path.replace(path)


def archive_job(
    job: ArchiveJob,
    service_date: date,
    db_url: str,
    data_dir: Path,
    overwrite: bool,
) -> None:
    output_dir = archive_dir(data_dir, job, service_date)
    data_path = output_dir / "data.parquet"
    manifest_path = output_dir / "manifest.json"

    if data_path.exists() and not overwrite:
        print(f"[skip] {job.archive_name} {service_date}: {data_path} already exists")
        return

    output_dir.mkdir(parents=True, exist_ok=True)
    if overwrite:
        remove_outputs(output_dir)

    print(f"[read] {job.archive_name} {service_date} from {job.source_table}")
    df = read_database(job.select_query, db_url)
    db_count = read_count(job.count_query, db_url)

    if df.height != db_count:
        raise ArchiveError(
            f"{job.archive_name} DB read count mismatch: frame={df.height}, db={db_count}"
        )

    quality_counts = job.quality_fn(df)
    temp_data_path = data_path.with_suffix(".parquet.tmp")

    try:
        df.write_parquet(temp_data_path)
        temp_data_path.replace(data_path)

        written_count = parquet_row_count(data_path)
        if written_count != db_count:
            remove_outputs(output_dir)
            raise ArchiveError(
                f"{job.archive_name} parquet count mismatch: parquet={written_count}, db={db_count}"
            )

        manifest = {
            "archive_name": job.archive_name,
            "source_table": job.source_table,
            "service_date": service_date.isoformat(),
            "row_count": written_count,
            "db_count": db_count,
            "quality_counts": quality_counts,
            "schema_version": SCHEMA_VERSION,
            "query_version": QUERY_VERSION,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "filters": job.filters,
            "data_file": data_path.name,
        }
        write_manifest(manifest_path, manifest)
    except Exception:
        if temp_data_path.exists():
            temp_data_path.unlink()
        if data_path.exists() and not manifest_path.exists():
            data_path.unlink()
        raise

    print(f"[done] {job.archive_name} {service_date}: rows={written_count}")


def main() -> int:
    args = parse_args()

    try:
        service_dates = service_dates_from_args(args)
        db_url, data_dir = load_config()
        for service_date in service_dates:
            for job in build_jobs(service_date):
                archive_job(job, service_date, db_url, data_dir, args.overwrite)
    except (ArchiveError, OSError, RuntimeError, pl.exceptions.PolarsError) as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
