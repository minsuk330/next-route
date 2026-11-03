"""Compare LightGBM / XGBoost / CatBoost under one identical split.

train.py is the reference LightGBM run. This script reuses its split, feature list,
pandas conversion and metric code verbatim (via import) so the only thing that differs
between models is the learner itself. Anything else would make the MAE numbers
incomparable.

All three learners are configured with an L1 (MAE) objective because metrics.py scores
MAE — leaving a learner on its default L2 objective silently handicaps it.

    python train_compare.py 2026-06-12,2026-06-13 --target label --test-dates 2026-06-13
    python train_compare.py "$DATES" --target label --test-dates "$TEST" \
      --per-date-sample-rows 200000 --models lightgbm,xgboost,catboost
"""

from __future__ import annotations

import argparse
import sys
import time
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import pandas as pd
import polars as pl

from metrics import evaluate_predictions, print_reports, write_json
from split import HORIZON_BUCKETS, TARGET_COLUMNS, create_split, load_data_dir
from train import (
    CATEGORICAL_FEATURES,
    LEAKAGE_COLUMNS,
    categorical_dtypes,
    experiment_dir,
    feature_columns,
    git_commit,
    parse_service_dates,
    service_date_label,
    to_pandas,
    validation_split,
)


ALL_MODELS = ("lightgbm", "xgboost", "catboost")
API_PREDICTION_COLUMN = "api_target_seconds_to_arrival"

# Weekly route rotation means these absolute stop-sequence indices carry
# little signal for a route the model has never seen: a "3rd stop of 40" pattern learned
# on one route's numbering does not transfer to another route's numbering. Converting to
# 0-1 ratios of route length is the route-agnostic replacement.
ROUTE_LENGTH_DEPENDENT_FEATURES = [
    "current_section_order",
    "target_seq",
    "remaining_stop_count",
]
ROUTE_RELATIVE_FEATURES = [
    "current_position_ratio",
    "target_position_ratio",
    "remaining_stop_ratio",
]

# Shared where the learners have a comparable knob. LightGBM num_leaves=64 is the
# reference from train.py; XGBoost mirrors it with lossguide+max_leaves and CatBoost's
# symmetric depth=6 is the same 64-leaf budget.
LEARNING_RATE = 0.05
N_ESTIMATORS = 2000
EARLY_STOPPING_ROUNDS = 50


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train and compare gradient boosting models on one shared split."
    )
    parser.add_argument("service_date", help="Comma separated YYYY-MM-DD service dates.")
    parser.add_argument("--target", choices=sorted(TARGET_COLUMNS), default="label")
    parser.add_argument("--sample-rows", type=int, default=2_000_000)
    parser.add_argument("--per-date-sample-rows", type=int, default=None)
    parser.add_argument("--test-from", default="21:00")
    parser.add_argument("--test-dates", default=None)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--with-api-feature", action="store_true")
    parser.add_argument(
        "--models",
        default=",".join(ALL_MODELS),
        help=f"Comma separated subset of {','.join(ALL_MODELS)}.",
    )
    parser.add_argument(
        "--no-route-id",
        action="store_true",
        help=(
            "Drop route_id from the feature set. Routes rotate weekly (see split.split_last_days_per_route), "
            "so a test-week route may have little or no training history — route-specific "
            "learning cannot transfer and may actively hurt. This trains route-agnostic."
        ),
    )
    parser.add_argument(
        "--route-agnostic",
        action="store_true",
        help=(
            "Full route-agnostic mode: drop route_id AND replace the route-length-dependent "
            "absolute indices (current_section_order, target_seq, remaining_stop_count) with "
            "0-1 ratios of estimated route length. Implies --no-route-id. See "
            "add_route_relative_features() for the route-length estimate's caveat."
        ),
    )
    parser.add_argument(
        "--keep-null-features",
        action="store_true",
        help=(
            "Keep feature columns that are entirely null in train. They are dropped by "
            "default (weather/holiday sidecars may be absent); trees cannot split on an "
            "all-null column so dropping is metric-neutral and avoids learner edge cases."
        ),
    )
    return parser.parse_args()


def parse_models(value: str) -> list[str]:
    names = [item.strip().lower() for item in value.split(",") if item.strip()]
    unknown = [name for name in names if name not in ALL_MODELS]
    if unknown:
        raise ValueError(f"Unknown model(s): {', '.join(unknown)}. Choose from {ALL_MODELS}.")
    if not names:
        raise ValueError("At least one model is required.")
    return names


def add_route_relative_features(
    train: pl.DataFrame, test: pl.DataFrame
) -> tuple[pl.DataFrame, pl.DataFrame]:
    """Convert route-length-dependent absolute indices into 0-1 ratios of route length.

    A route's true stop count is static schedule data (published by the transit agency,
    known the moment a route is registered — not something that needs arrival history to
    know). We do not have that as a sidecar here, so we approximate it as the max observed
    current_section_order/target_seq for the route across train+test. This is a lower-bound
    estimate: a route whose buses never reached their final stop within the sampled snapshots
    will look shorter than it really is. Using train+test (not train-only) to build the
    estimate is not label leakage — route length is structural metadata, not the arrival-time
    target — but it does mean routes appearing only in test still get a length estimate here,
    which a real deployment would get from the schedule instead.
    """
    combined = pl.concat(
        [
            train.select(["route_id", "current_section_order", "target_seq"]),
            test.select(["route_id", "current_section_order", "target_seq"]),
        ]
    )
    route_length = combined.group_by("route_id").agg(
        pl.max_horizontal(
            pl.col("current_section_order").max(), pl.col("target_seq").max()
        ).alias("route_total_stops")
    )

    def attach(frame: pl.DataFrame) -> pl.DataFrame:
        total = pl.col("route_total_stops")
        safe_total = pl.when(total > 0).then(total).otherwise(None)
        return frame.join(route_length, on="route_id", how="left").with_columns(
            [
                (pl.col("current_section_order") / safe_total).alias(
                    "current_position_ratio"
                ),
                (pl.col("target_seq") / safe_total).alias("target_position_ratio"),
                (pl.col("remaining_stop_count") / safe_total).alias(
                    "remaining_stop_ratio"
                ),
            ]
        )

    return attach(train), attach(test)


def all_null_features(frame: pl.DataFrame, columns: list[str]) -> list[str]:
    return [
        column
        for column in columns
        if column in frame.columns and frame[column].null_count() == frame.height
    ]


def train_lightgbm(
    x_train: pd.DataFrame,
    y_train: pd.Series,
    x_valid: pd.DataFrame,
    y_valid: pd.Series,
    x_test: pd.DataFrame,
    seed: int,
) -> tuple[Any, dict[str, Any], Callable[[Path], str]]:
    import lightgbm as lgb

    model = lgb.LGBMRegressor(
        objective="mae",
        n_estimators=N_ESTIMATORS,
        learning_rate=LEARNING_RATE,
        num_leaves=64,
        subsample=0.9,
        colsample_bytree=0.9,
        random_state=seed,
        n_jobs=-1,
    )
    model.fit(
        x_train,
        y_train,
        eval_set=[(x_valid, y_valid)],
        eval_metric="mae",
        categorical_feature=[
            column for column in CATEGORICAL_FEATURES if column in x_train.columns
        ],
        callbacks=[
            lgb.early_stopping(stopping_rounds=EARLY_STOPPING_ROUNDS),
            lgb.log_evaluation(period=100),
        ],
    )
    best_iteration = int(model.best_iteration_ or model.n_estimators)
    predictions = model.predict(x_test, num_iteration=model.best_iteration_)

    def save(directory: Path) -> str:
        path = directory / "model_lightgbm.txt"
        model.booster_.save_model(str(path))
        return str(path)

    return predictions, {"best_iteration": best_iteration}, save


def train_xgboost(
    x_train: pd.DataFrame,
    y_train: pd.Series,
    x_valid: pd.DataFrame,
    y_valid: pd.Series,
    x_test: pd.DataFrame,
    seed: int,
) -> tuple[Any, dict[str, Any], Callable[[Path], str]]:
    import xgboost as xgb

    model = xgb.XGBRegressor(
        # reg:absoluteerror needs XGBoost >= 2.0; it is the L1 counterpart of
        # LightGBM objective="mae".
        objective="reg:absoluteerror",
        eval_metric="mae",
        tree_method="hist",
        # route_id arrives as a pandas category dtype from to_pandas(). max_cat_to_onehot=1
        # forces partition-based splits instead of one-hot, which is what high-cardinality
        # route_id needs.
        enable_categorical=True,
        max_cat_to_onehot=1,
        grow_policy="lossguide",
        max_leaves=64,
        max_depth=0,
        n_estimators=N_ESTIMATORS,
        learning_rate=LEARNING_RATE,
        subsample=0.9,
        colsample_bytree=0.9,
        random_state=seed,
        n_jobs=-1,
        early_stopping_rounds=EARLY_STOPPING_ROUNDS,
    )
    model.fit(x_train, y_train, eval_set=[(x_valid, y_valid)], verbose=100)

    best_iteration = getattr(model, "best_iteration", None)
    if best_iteration is None:
        predictions = model.predict(x_test)
        best_iteration = N_ESTIMATORS
    else:
        predictions = model.predict(x_test, iteration_range=(0, int(best_iteration) + 1))

    def save(directory: Path) -> str:
        path = directory / "model_xgboost.json"
        model.save_model(str(path))
        return str(path)

    return predictions, {"best_iteration": int(best_iteration)}, save


def catboost_frame(frame: pd.DataFrame) -> pd.DataFrame:
    """CatBoost rejects NaN inside categorical features and does not take pandas
    category dtype, so route_id becomes a plain string with an explicit missing token."""
    prepared = frame.copy()
    for column in CATEGORICAL_FEATURES:
        if column in prepared.columns:
            prepared[column] = (
                prepared[column].astype("object").fillna("__missing__").astype(str)
            )
    return prepared


def train_catboost(
    x_train: pd.DataFrame,
    y_train: pd.Series,
    x_valid: pd.DataFrame,
    y_valid: pd.Series,
    x_test: pd.DataFrame,
    seed: int,
) -> tuple[Any, dict[str, Any], Callable[[Path], str]]:
    from catboost import CatBoostRegressor, Pool

    columns = list(x_train.columns)
    cat_indices = [
        columns.index(column) for column in CATEGORICAL_FEATURES if column in columns
    ]
    train_pool = Pool(catboost_frame(x_train), y_train, cat_features=cat_indices)
    valid_pool = Pool(catboost_frame(x_valid), y_valid, cat_features=cat_indices)

    model = CatBoostRegressor(
        loss_function="MAE",
        eval_metric="MAE",
        iterations=N_ESTIMATORS,
        learning_rate=LEARNING_RATE,
        # Symmetric trees: depth 6 is the same 64-leaf budget as LightGBM num_leaves=64.
        depth=6,
        random_seed=seed,
        thread_count=-1,
        verbose=100,
    )
    model.fit(
        train_pool,
        eval_set=valid_pool,
        early_stopping_rounds=EARLY_STOPPING_ROUNDS,
        use_best_model=True,
    )
    predictions = model.predict(catboost_frame(x_test))

    def save(directory: Path) -> str:
        path = directory / "model_catboost.cbm"
        model.save_model(str(path))
        return str(path)

    return predictions, {"best_iteration": int(model.get_best_iteration())}, save


TRAINERS: dict[str, Callable[..., tuple[Any, dict[str, Any], Callable[[Path], str]]]] = {
    "lightgbm": train_lightgbm,
    "xgboost": train_xgboost,
    "catboost": train_catboost,
}


def comparison_rows(reports: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    for name, report in reports.items():
        overall = report["overall"]
        row = {
            "model": name,
            "count": overall["count"],
            "mae": overall["mae"],
            "rmse": overall["rmse"],
            "p50": overall["p50_abs_error"],
            "p90": overall["p90_abs_error"],
        }
        for bucket in HORIZON_BUCKETS:
            bucket_report = report.get("by_horizon", {}).get(bucket)
            row[bucket] = bucket_report["mae"] if bucket_report else float("nan")
        rows.append(row)
    return sorted(rows, key=lambda item: item["mae"])


def format_comparison(rows: list[dict[str, Any]], timings: dict[str, float]) -> str:
    header = (
        f"| {'model':<12} | {'n':>9} | {'MAE':>8} | {'RMSE':>8} | {'p50':>7} | {'p90':>8} | "
        + " | ".join(f"{bucket:>7}" for bucket in HORIZON_BUCKETS)
        + " | fit(s) |"
    )
    divider = (
        "|" + "-" * 14 + "|" + "-" * 11 + "|" + "-" * 10 + "|" + "-" * 10 + "|"
        + "-" * 9 + "|" + "-" * 10 + "|"
        + "|".join("-" * 9 for _ in HORIZON_BUCKETS)
        + "|--------|"
    )
    lines = [
        "MAE in seconds, lower is better. Horizon columns are MAE within that bucket.",
        "",
        header,
        divider,
    ]
    for row in rows:
        buckets = " | ".join(f"{row[bucket]:>7.1f}" for bucket in HORIZON_BUCKETS)
        fit_seconds = timings.get(row["model"])
        fit_text = f"{fit_seconds:6.0f}" if fit_seconds is not None else "     -"
        lines.append(
            f"| {row['model']:<12} | {row['count']:>9,} | {row['mae']:>8.2f} | "
            f"{row['rmse']:>8.2f} | {row['p50']:>7.2f} | {row['p90']:>8.2f} | "
            f"{buckets} | {fit_text} |"
        )
    return "\n".join(lines)


def run(args: argparse.Namespace) -> dict[str, Any]:
    models = parse_models(args.models)
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
    target_column = split.target_column

    route_agnostic = args.route_agnostic or args.no_route_id
    if args.route_agnostic:
        train_frame, test_frame = add_route_relative_features(split.train, split.test)
        split = replace(split, train=train_frame, test=test_frame)

    columns = feature_columns(args.with_api_feature)
    if route_agnostic:
        columns = [column for column in columns if column not in CATEGORICAL_FEATURES]
        if args.route_agnostic:
            columns = [
                column
                for column in columns
                if column not in ROUTE_LENGTH_DEPENDENT_FEATURES
            ] + ROUTE_RELATIVE_FEATURES
            print(
                "[info] route-agnostic mode: route_id dropped, absolute position "
                f"indices replaced with route-relative ratios ({len(columns)} features)"
            )
        else:
            print(f"[info] route_id dropped — training route-agnostic ({len(columns)} features)")
    dropped: list[str] = []
    if not args.keep_null_features:
        dropped = all_null_features(split.train, columns)
        if dropped:
            print(f"[info] dropping {len(dropped)} all-null feature(s): {', '.join(dropped)}")
            columns = [column for column in columns if column not in dropped]

    print(
        f"[info] train_rows={split.train.height:,} test_rows={split.test.height:,} "
        f"features={len(columns)} target={target_column}"
    )

    cat_dtypes = categorical_dtypes([split.train, split.test])
    x_train, y_train, x_valid, y_valid = validation_split(
        split.train, target_column, columns, cat_dtypes
    )
    x_test, _ = to_pandas(split.test, columns, target_column, cat_dtypes)

    reports: dict[str, dict[str, Any]] = {}
    timings: dict[str, float] = {}
    model_info: dict[str, dict[str, Any]] = {}
    model_files: dict[str, str] = {}

    # Free reference point: the upstream API's own ETA, scored on the same test rows.
    # Skipped when the API column is the target itself (error would be zero by definition).
    if args.target != "api" and API_PREDICTION_COLUMN in split.test.columns:
        reports["baseline_api"] = evaluate_predictions(
            split.test, target_column, API_PREDICTION_COLUMN
        )

    date_label = service_date_label(service_dates)
    run_id = (
        f"compare_{date_label}_{args.target}_"
        f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    )
    output_dir = experiment_dir(data_dir, run_id)

    for name in models:
        print(f"\n[train] {name}")
        started = time.perf_counter()
        try:
            predictions, info, save = TRAINERS[name](
                x_train, y_train, x_valid, y_valid, x_test, args.seed
            )
        except ImportError as exc:
            print(f"[warn] skipping {name}: {exc}", file=sys.stderr)
            continue
        timings[name] = time.perf_counter() - started
        model_info[name] = info
        model_files[name] = save(output_dir)

        prediction_column = f"prediction_{name}"
        scored = split.test.with_columns(pl.Series(prediction_column, predictions))
        reports[name] = evaluate_predictions(scored, target_column, prediction_column)
        print(f"[done] {name} fit={timings[name]:.0f}s mae={reports[name]['overall']['mae']:.2f}")

    if not any(name in reports for name in models):
        raise RuntimeError("No model finished training.")

    rows = comparison_rows(reports)
    table = format_comparison(rows, timings)

    manifest = {
        "run_id": run_id,
        "type": "model_comparison",
        "schema_version": 2,
        "models": models,
        "service_date_range": [item.isoformat() for item in service_dates],
        "dataset_path": split.dataset_path,
        "target_policy": args.target,
        "target_column": target_column,
        "feature_list": columns,
        "dropped_all_null_features": dropped,
        "no_route_id": args.no_route_id,
        "route_agnostic": args.route_agnostic,
        "leakage_columns_excluded": [
            column for column in LEAKAGE_COLUMNS if column not in columns
        ],
        "with_api_feature": args.with_api_feature,
        "sampling_policy": split.sampling_policy,
        "split_policy": split.split_policy,
        "train_rows": split.train.height,
        "test_rows": split.test.height,
        "validation_rows": len(y_valid),
        "training_route_categories": sorted(
            str(value)
            for value in split.train[CATEGORICAL_FEATURES[0]].unique().to_list()
            if value is not None
        ),
        "model_info": model_info,
        "model_files": model_files,
        "fit_seconds": timings,
        "metrics": reports,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit(),
    }

    write_json(output_dir / "metrics.json", reports)
    write_json(output_dir / "training_manifest.json", manifest)
    (output_dir / "comparison.md").write_text(table + "\n", encoding="utf-8")

    print_reports(reports)
    print("\n" + table)
    print(f"\noutput={output_dir}")
    return manifest


def main() -> int:
    args = parse_args()
    try:
        run(args)
    except Exception as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
