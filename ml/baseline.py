from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Literal

import polars as pl

from metrics import evaluate_predictions, print_reports, write_json
from split import TARGET_COLUMNS, TargetName, create_split, load_data_dir


Target = Literal["api", "label", "corrected"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate bus ML non-ML baselines.")
    parser.add_argument("service_date", help="Service date in YYYY-MM-DD format.")
    parser.add_argument(
        "--target",
        choices=sorted(TARGET_COLUMNS),
        default="label",
        help="Target column family to evaluate.",
    )
    parser.add_argument("--sample-rows", type=int, default=2_000_000)
    parser.add_argument(
        "--per-date-sample-rows",
        type=int,
        default=None,
        help="Rows to sample per service_date before concatenating multi-day data.",
    )
    parser.add_argument("--test-from", default="21:00")
    parser.add_argument("--test-dates", default=None)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def parse_service_dates(value: str) -> list[date]:
    parsed = sorted(
        {date.fromisoformat(item.strip()) for item in value.split(",") if item.strip()}
    )
    if not parsed:
        raise ValueError("At least one service_date is required.")
    return parsed


def service_date_label(service_dates: list[date]) -> str:
    if len(service_dates) == 1:
        return service_dates[0].isoformat()
    return f"{service_dates[0].isoformat()}_to_{service_dates[-1].isoformat()}"


def git_commit() -> str | None:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def experiment_dir(data_dir: Path, run_id: str) -> Path:
    path = data_dir / "experiments" / run_id
    path.mkdir(parents=True, exist_ok=True)
    return path


def add_api_baseline(test: pl.DataFrame) -> pl.DataFrame:
    return test.with_columns(
        pl.col("api_target_seconds_to_arrival").alias("prediction_api")
    )


def median_lookup_prediction(
    train: pl.DataFrame,
    test: pl.DataFrame,
    target_column: str,
) -> pl.DataFrame:
    primary_keys = [
        "route_id",
        "current_section_order",
        "target_seq",
        "hour_of_day",
        "day_of_week",
    ]
    fallback_keys = ["route_id", "target_seq", "hour_of_day"]
    overall_median = float(train.select(pl.col(target_column).median()).item())

    primary = train.group_by(primary_keys).agg(
        pl.col(target_column).median().alias("_median_primary")
    )
    fallback = train.group_by(fallback_keys).agg(
        pl.col(target_column).median().alias("_median_fallback")
    )

    return (
        test.join(primary, on=primary_keys, how="left")
        .join(fallback, on=fallback_keys, how="left")
        .with_columns(
            pl.coalesce(
                [
                    pl.col("_median_primary"),
                    pl.col("_median_fallback"),
                    pl.lit(overall_median),
                ]
            ).alias("prediction_median")
        )
        .drop(["_median_primary", "_median_fallback"])
    )


def run_baselines(args: argparse.Namespace) -> dict[str, Any]:
    service_dates = parse_service_dates(args.service_date)
    data_dir = load_data_dir()
    split = create_split(
        service_date=service_dates,
        target_name=args.target,
        sample_rows=args.sample_rows,
        per_date_sample_rows=args.per_date_sample_rows,
        test_from=args.test_from,
        test_dates=args.test_dates,
        seed=args.seed,
        data_dir=data_dir,
    )

    test = add_api_baseline(split.test)
    test = median_lookup_prediction(split.train, test, split.target_column)

    reports = {
        "baseline_api": evaluate_predictions(
            test, split.target_column, "prediction_api"
        ),
        "baseline_median": evaluate_predictions(
            test, split.target_column, "prediction_median"
        ),
    }
    if args.target == "api":
        note = "self-comparison (prediction == target), MAE=0 by construction"
        reports["baseline_api"]["note"] = note
        print(f"[warn] baseline_api: {note}")

    date_label = service_date_label(service_dates)
    run_id = (
        f"baseline_{date_label}_{args.target}_"
        f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    )
    output_dir = experiment_dir(data_dir, run_id)

    payload = {
        "run_id": run_id,
        "type": "baseline",
        "service_date_range": [item.isoformat() for item in service_dates],
        "target_name": args.target,
        "target_column": split.target_column,
        "dataset_path": split.dataset_path,
        "split_policy": split.split_policy,
        "sampling_policy": split.sampling_policy,
        "train_rows": split.train.height,
        "test_rows": split.test.height,
        "metrics": reports,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit(),
    }
    write_json(output_dir / "baseline_report.json", payload)
    print_reports(reports)
    print(f"\nreport={output_dir / 'baseline_report.json'}")
    return payload


def main() -> int:
    args = parse_args()
    try:
        run_baselines(args)
    except Exception as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
