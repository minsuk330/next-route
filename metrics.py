from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
import polars as pl


def regression_metrics(errors: np.ndarray) -> dict[str, float | int]:
    abs_error = np.abs(errors)
    return {
        "count": int(errors.size),
        "mae": float(abs_error.mean()) if errors.size else float("nan"),
        "rmse": float(np.sqrt(np.mean(errors**2))) if errors.size else float("nan"),
        "p50_abs_error": float(np.quantile(abs_error, 0.5)) if errors.size else float("nan"),
        "p90_abs_error": float(np.quantile(abs_error, 0.9)) if errors.size else float("nan"),
    }


def evaluate_predictions(
    frame: pl.DataFrame,
    target_column: str,
    prediction_column: str,
    route_worst_limit: int = 5,
) -> dict[str, Any]:
    scored = frame.with_columns(
        [
            (pl.col(prediction_column) - pl.col(target_column)).alias("_error"),
            (pl.col(prediction_column) - pl.col(target_column)).abs().alias("_abs_error"),
        ]
    ).filter(
        pl.col(prediction_column).is_not_null() & pl.col(target_column).is_not_null()
    )

    errors = scored["_error"].to_numpy()
    overall = regression_metrics(errors)

    by_horizon: dict[str, Any] = {}
    if "horizon_bucket" in scored.columns:
        for row in scored.group_by("horizon_bucket").agg(pl.col("_error")).to_dicts():
            by_horizon[str(row["horizon_bucket"])] = regression_metrics(
                np.asarray(row["_error"], dtype=float)
            )

    by_label_source: dict[str, Any] = {}
    if "label_source" in scored.columns:
        for row in scored.group_by("label_source").agg(pl.col("_error")).to_dicts():
            by_label_source[str(row["label_source"])] = regression_metrics(
                np.asarray(row["_error"], dtype=float)
            )

    route_rows = (
        scored.group_by("route_id")
        .agg(
            [
                pl.len().alias("count"),
                pl.col("_abs_error").mean().alias("mae"),
                pl.col("_abs_error").quantile(0.9).alias("p90_abs_error"),
            ]
        )
        .sort("mae", descending=True)
        .head(route_worst_limit)
        .to_dicts()
    )
    worst_routes = [
        {
            "route_id": str(row["route_id"]),
            "count": int(row["count"]),
            "mae": float(row["mae"]),
            "p90_abs_error": float(row["p90_abs_error"]),
        }
        for row in route_rows
    ]

    return {
        "overall": overall,
        "by_horizon": by_horizon,
        "by_label_source": by_label_source,
        "worst_routes": worst_routes,
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_suffix(".json.tmp")
    with temp_path.open("w", encoding="utf-8") as file:
        json.dump(payload, file, ensure_ascii=False, indent=2, sort_keys=True)
        file.write("\n")
    temp_path.replace(path)


def print_metric_block(name: str, report: dict[str, Any]) -> None:
    overall = report["overall"]
    print(f"\n{name}")
    print("-" * len(name))
    print(
        "overall "
        f"n={overall['count']} "
        f"mae={overall['mae']:.2f} "
        f"rmse={overall['rmse']:.2f} "
        f"p50={overall['p50_abs_error']:.2f} "
        f"p90={overall['p90_abs_error']:.2f}"
    )

    if report.get("by_horizon"):
        print("by_horizon")
        for key, value in sorted(report["by_horizon"].items()):
            print(
                f"  {key:>7} n={value['count']} "
                f"mae={value['mae']:.2f} p90={value['p90_abs_error']:.2f}"
            )

    if report.get("by_label_source"):
        print("by_label_source")
        for key, value in sorted(report["by_label_source"].items()):
            print(
                f"  {key} n={value['count']} "
                f"mae={value['mae']:.2f} p90={value['p90_abs_error']:.2f}"
            )

    if report.get("worst_routes"):
        print("worst_routes")
        for row in report["worst_routes"]:
            print(
                f"  {row['route_id']} n={row['count']} "
                f"mae={row['mae']:.2f} p90={row['p90_abs_error']:.2f}"
            )


def print_reports(reports: dict[str, dict[str, Any]]) -> None:
    for name, report in reports.items():
        print_metric_block(name, report)
