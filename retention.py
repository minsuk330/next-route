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
import psycopg
from psycopg import sql
from dotenv import load_dotenv


ARCHIVE_TABLES = ("bus_label", "bus_position", "bus_candidate")


class RetentionError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Dry-run or apply bus ML raw/dataset retention."
    )
    parser.add_argument("--retention-days", type=int, default=21)
    parser.add_argument("--dataset-cache-days", type=int, default=7)
    parser.add_argument("--chunk-size", type=int, default=10_000)
    parser.add_argument("--dry-run", action="store_true", help="Print targets only.")
    parser.add_argument("--apply", action="store_true", help="Delete gated raw rows.")
    parser.add_argument(
        "--confirm-backup-reviewed",
        action="store_true",
        help="Required with --apply because local parquet may become the only copy.",
    )
    return parser.parse_args()


def load_config() -> tuple[str, Path]:
    ml_dir = Path(__file__).resolve().parent
    load_dotenv(ml_dir / ".env")

    db_url = os.getenv("DB_URL")
    if not db_url:
        raise RetentionError("DB_URL is required. Create ml/.env from .env.example.")

    data_dir_value = os.getenv("DATA_DIR", "./data")
    data_dir = Path(data_dir_value).expanduser()
    if not data_dir.is_absolute():
        data_dir = ml_dir / data_dir
    return db_url, data_dir


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def read_database(query: str, db_url: str) -> pl.DataFrame:
    return pl.read_database_uri(query, db_url)


def read_scalar(query: str, db_url: str) -> int:
    result = read_database(query, db_url)
    if result.height != 1 or result.width != 1:
        raise RetentionError(f"Query returned unexpected shape: {result.shape}")
    return int(result.item(0, 0))


def partition_dir(data_dir: Path, table: str, service_date: date) -> Path:
    return data_dir / table / f"service_date={service_date.isoformat()}"


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_suffix(".json.tmp")
    with temp_path.open("w", encoding="utf-8") as file:
        json.dump(payload, file, ensure_ascii=False, indent=2, sort_keys=True)
        file.write("\n")
    temp_path.replace(path)


def parquet_row_count(path: Path) -> int:
    return int(pl.scan_parquet(str(path)).select(pl.len()).collect().item())


def archive_dates(data_dir: Path, table: str) -> set[date]:
    root = data_dir / table
    if not root.exists():
        return set()
    dates: set[date] = set()
    for item in root.glob("service_date=*"):
        if not item.is_dir():
            continue
        value = item.name.removeprefix("service_date=")
        try:
            dates.add(date.fromisoformat(value))
        except ValueError:
            continue
    return dates


def verify_archive(data_dir: Path, table: str, service_date: date) -> dict[str, Any]:
    output_dir = partition_dir(data_dir, table, service_date)
    data_path = output_dir / "data.parquet"
    manifest_path = output_dir / "manifest.json"
    errors: list[str] = []
    row_count: int | None = None
    manifest_count: int | None = None

    if not data_path.exists():
        errors.append(f"missing parquet: {data_path}")
    else:
        row_count = parquet_row_count(data_path)

    if not manifest_path.exists():
        errors.append(f"missing manifest: {manifest_path}")
    else:
        manifest = load_json(manifest_path)
        manifest_count = manifest.get("row_count")
        if row_count is not None and manifest_count != row_count:
            errors.append(
                f"row_count mismatch: manifest={manifest_count}, parquet={row_count}"
            )

    return {
        "table": table,
        "service_date": service_date.isoformat(),
        "row_count": row_count,
        "manifest_row_count": manifest_count,
        "passed": not errors,
        "errors": errors,
    }


def db_date_counts(db_url: str, cutoff_date: date) -> dict[str, dict[str, int]]:
    cutoff_sql = sql_string(cutoff_date.isoformat())
    queries = {
        "bus_arrival_label_event": f"""
            select service_date::date as service_date, count(*) as row_count
            from bus_arrival_label_event
            where service_date < date {cutoff_sql}
            group by 1
            order by 1
        """,
        "bus_position_raw": f"""
            select (collected_at - interval '4 hours')::date as service_date,
                   count(*) as row_count
            from bus_position_raw
            where collected_at < timestamp {sql_string(cutoff_date.isoformat() + " 04:00:00")}
            group by 1
            order by 1
        """,
        "bus_arrival_candidate_raw": f"""
            select (finalized_at - interval '4 hours')::date as service_date,
                   count(*) as row_count
            from bus_arrival_candidate_raw
            where finalized_at is not null
              and finalized_at < timestamp {sql_string(cutoff_date.isoformat() + " 04:00:00")}
            group by 1
            order by 1
        """,
    }
    result: dict[str, dict[str, int]] = {}
    for table, query in queries.items():
        frame = read_database(query, db_url)
        result[table] = {
            str(row["service_date"]): int(row["row_count"])
            for row in frame.to_dicts()
        }
    return result


def gated_service_dates(
    data_dir: Path,
    db_counts: dict[str, dict[str, int]],
    cutoff_date: date,
) -> tuple[list[date], list[dict[str, Any]]]:
    db_dates = {
        date.fromisoformat(value)
        for counts in db_counts.values()
        for value in counts
        if date.fromisoformat(value) < cutoff_date
    }
    archive_date_union = set().union(
        *(archive_dates(data_dir, table) for table in ARCHIVE_TABLES)
    )
    service_dates = sorted((db_dates | archive_date_union))

    gated: list[date] = []
    reports: list[dict[str, Any]] = []
    for service_date in service_dates:
        if service_date >= cutoff_date:
            continue
        archives = [
            verify_archive(data_dir, table, service_date)
            for table in ARCHIVE_TABLES
        ]
        passed = all(item["passed"] for item in archives)
        if passed and any(
            str(service_date) in counts for counts in db_counts.values()
        ):
            gated.append(service_date)
        reports.append(
            {
                "service_date": service_date.isoformat(),
                "passed": passed,
                "archives": archives,
                "db_counts": {
                    table: counts.get(service_date.isoformat(), 0)
                    for table, counts in db_counts.items()
                },
            }
        )
    return gated, reports


def service_window(service_date: date) -> tuple[str, str]:
    start = f"{service_date.isoformat()} 04:00:00"
    end = f"{(service_date + timedelta(days=1)).isoformat()} 04:00:00"
    return start, end


def count_delete_targets(db_url: str, service_date: date) -> dict[str, int]:
    start, end = service_window(service_date)
    date_sql = sql_string(service_date.isoformat())
    start_sql = sql_string(start)
    end_sql = sql_string(end)
    return {
        "bus_position_raw": read_scalar(
            f"""
            select count(*)
            from bus_position_raw
            where collected_at >= timestamp {start_sql}
              and collected_at < timestamp {end_sql}
            """,
            db_url,
        ),
        "bus_arrival_candidate_raw": read_scalar(
            f"""
            select count(*)
            from bus_arrival_candidate_raw
            where finalized_at >= timestamp {start_sql}
              and finalized_at < timestamp {end_sql}
            """,
            db_url,
        ),
        "bus_arrival_label_event": read_scalar(
            f"""
            select count(*)
            from bus_arrival_label_event
            where service_date = date {date_sql}
            """,
            db_url,
        ),
    }


def delete_chunked(
    connection: psycopg.Connection[Any],
    table: str,
    condition: str,
    chunk_size: int,
) -> int:
    total = 0
    query = sql.SQL(
        """
        with deleted as (
            delete from {table}
            where ctid in (
                select ctid
                from {table}
                where {condition}
                limit %s
            )
            returning 1
        )
        select count(*) from deleted
        """
    ).format(
        table=sql.Identifier(table),
        condition=sql.SQL(condition),
    )
    while True:
        with connection.cursor() as cursor:
            cursor.execute(query, (chunk_size,))
            row = cursor.fetchone()
            deleted = int(row[0]) if row else 0
        connection.commit()
        total += deleted
        if deleted == 0:
            return total


def delete_for_service_date(
    db_url: str,
    service_date: date,
    chunk_size: int,
) -> dict[str, int]:
    start, end = service_window(service_date)
    date_sql = sql_string(service_date.isoformat())
    start_sql = sql_string(start)
    end_sql = sql_string(end)
    with psycopg.connect(db_url) as connection:
        try:
            return {
                "bus_position_raw": delete_chunked(
                    connection,
                    "bus_position_raw",
                    f"collected_at >= timestamp {start_sql} and collected_at < timestamp {end_sql}",
                    chunk_size,
                ),
                "bus_arrival_candidate_raw": delete_chunked(
                    connection,
                    "bus_arrival_candidate_raw",
                    f"finalized_at >= timestamp {start_sql} and finalized_at < timestamp {end_sql}",
                    chunk_size,
                ),
                "bus_arrival_label_event": delete_chunked(
                    connection,
                    "bus_arrival_label_event",
                    f"service_date = date {date_sql}",
                    chunk_size,
                ),
            }
        except Exception:
            connection.rollback()
            raise


def dataset_cache_targets(data_dir: Path, cutoff_date: date) -> list[Path]:
    root = data_dir / "dataset"
    if not root.exists():
        return []
    targets: list[Path] = []
    for item in root.glob("service_date=*"):
        if not item.is_dir():
            continue
        value = item.name.removeprefix("service_date=")
        try:
            service_date = date.fromisoformat(value)
        except ValueError:
            continue
        if service_date < cutoff_date:
            targets.append(item)
    return sorted(targets)


def run_retention(args: argparse.Namespace) -> dict[str, Any]:
    if args.apply and args.dry_run:
        raise RetentionError("Use either --dry-run or --apply, not both.")
    if args.apply and not args.confirm_backup_reviewed:
        raise RetentionError(
            "--apply requires --confirm-backup-reviewed because local parquet may become the only copy."
        )
    if args.retention_days < 1 or args.dataset_cache_days < 1:
        raise RetentionError("retention days must be positive.")
    if args.chunk_size < 1:
        raise RetentionError("--chunk-size must be positive.")

    db_url, data_dir = load_config()
    today = datetime.now().date()
    raw_cutoff_date = today - timedelta(days=args.retention_days)
    dataset_cutoff_date = today - timedelta(days=args.dataset_cache_days)
    dry_run = not args.apply

    db_counts = db_date_counts(db_url, raw_cutoff_date)
    gated_dates, gate_reports = gated_service_dates(data_dir, db_counts, raw_cutoff_date)
    delete_plan = {
        service_date.isoformat(): count_delete_targets(db_url, service_date)
        for service_date in gated_dates
    }

    deleted_rows: dict[str, dict[str, int]] = {}
    if not dry_run:
        for service_date in gated_dates:
            deleted_rows[service_date.isoformat()] = delete_for_service_date(
                db_url, service_date, args.chunk_size
            )

    dataset_targets = dataset_cache_targets(data_dir, dataset_cutoff_date)
    deleted_dataset_dirs: list[str] = []
    if not dry_run:
        for path in dataset_targets:
            shutil.rmtree(path)
            deleted_dataset_dirs.append(str(path))

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "dry_run": dry_run,
        "retention_days": args.retention_days,
        "dataset_cache_days": args.dataset_cache_days,
        "raw_cutoff_date": raw_cutoff_date.isoformat(),
        "dataset_cutoff_date": dataset_cutoff_date.isoformat(),
        "gated_service_dates": [item.isoformat() for item in gated_dates],
        "gate_reports": gate_reports,
        "delete_plan": delete_plan,
        "deleted_rows": deleted_rows,
        "dataset_cache_delete_targets": [str(path) for path in dataset_targets],
        "deleted_dataset_dirs": deleted_dataset_dirs,
    }

    output_dir = data_dir / "retention"
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    write_json(output_dir / f"retention_{stamp}.json", report)
    write_json(output_dir / "latest.json", report)
    return report


def print_report(report: dict[str, Any]) -> None:
    mode = "dry-run" if report["dry_run"] else "apply"
    print(f"Retention {mode}")
    print(f"raw_cutoff_date={report['raw_cutoff_date']}")
    print(f"dataset_cutoff_date={report['dataset_cutoff_date']}")

    print("\nGated raw service_dates")
    print("-----------------------")
    if not report["gated_service_dates"]:
        print("(none)")
    for service_date in report["gated_service_dates"]:
        print(service_date)
        for table, count in report["delete_plan"].get(service_date, {}).items():
            print(f"  {table}: {count}")

    preserved = [
        item for item in report["gate_reports"]
        if not item["passed"]
    ]
    print("\nGate failed / preserved")
    print("-----------------------")
    if not preserved:
        print("(none)")
    for item in preserved:
        print(item["service_date"])
        for archive in item["archives"]:
            if archive["passed"]:
                continue
            for error in archive["errors"]:
                print(f"  {archive['table']}: {error}")

    print("\nDataset cache targets")
    print("---------------------")
    if not report["dataset_cache_delete_targets"]:
        print("(none)")
    for path in report["dataset_cache_delete_targets"]:
        print(path)


def main() -> int:
    args = parse_args()
    try:
        report = run_retention(args)
        print_report(report)
    except (RetentionError, OSError, RuntimeError, pl.exceptions.PolarsError) as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
