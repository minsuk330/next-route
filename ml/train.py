from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

import lightgbm as lgb
import numpy as np
import pandas as pd
import polars as pl

from metrics import evaluate_predictions, print_reports, write_json
from split import TARGET_COLUMNS, create_split, load_data_dir, parse_test_dates
from feature_engineering import (
    SERVABLE_ENCODING_SPECS,
    SERVABLE_ROUTE_AGNOSTIC_SPECS,
    build_feature_frames,
    persist_encoding_artifacts,
)


NUMERIC_FEATURES = [
    "current_section_order",
    "section_progress",
    "current_section_distance",
    "current_full_section_distance",
    "next_stop_time",
    "last_stop_time",
    "congestion",
    "gps_x",
    "gps_y",
    "target_seq",
    "remaining_stop_count",
    "hour_of_day",
    "minute_of_day",
    "day_of_week",
    "is_weekend",
    # Calendar + weather (PR D2). Must exactly match the keys MlFeatureVectorBuilder
    # sends — snow_depth_cm/weather_severity are NOT served, so they stay out (else /predict 422).
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
    "is_raining",
    "is_snowing",
]
CATEGORICAL_FEATURES = ["route_id"]
LEAKAGE_COLUMNS = [
    "api_estimated_arrival_at",
    "corrected_arrival_at",
    "label_arrival_at",
    "api_target_seconds_to_arrival",
    "corrected_target_seconds_to_arrival",
    "label_target_seconds_to_arrival",
]

# Raw columns feature_engineering.build_feature_frames needs beyond NUMERIC_FEATURES/
# CATEGORICAL_FEATURES: fold hashing (arrival_label_event_id), the time-based split
# (service_date, snapshot_at), sampling dedup (position_raw_id), and route-history
# reporting (label_source). Only projected when --target-encoding narrows the load —
# see train_model().
ENCODING_SUPPORT_COLUMNS = [
    "service_date",
    "snapshot_at",
    "position_raw_id",
    "arrival_label_event_id",
    "label_source",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train LightGBM bus arrival model.")
    parser.add_argument("service_date", help="Service date in YYYY-MM-DD format.")
    parser.add_argument(
        "--target",
        choices=sorted(TARGET_COLUMNS),
        default="label",
        help="Target column family to train against.",
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
    parser.add_argument(
        "--with-api-feature",
        action="store_true",
        help="Include api_target_seconds_to_arrival as a feature for separate leakage-aware experiments.",
    )
    parser.add_argument(
        "--fallback-model",
        action="store_true",
        help=(
            "Also train a route-agnostic model B (same features minus route_id) and "
            "serve the routed combination — model A for routes seen in training, model B "
            "for the rest — as the reported/gated 'lightgbm' metric, instead of returning "
            "UNSUPPORTED_ROUTE for unseen routes at serve time."
        ),
    )
    parser.add_argument(
        "--target-encoding",
        action="store_true",
        help=(
            "Give model A route-keyed hierarchical target-encoding features (te_*), "
            "computed via full-data-encoding over the training window using the same "
            "deployable-only spec subset Stage 0 validated on 2026-08-15 (routed MAE "
            "117.67s vs the 138.53s plain baseline). Requires --fallback-model: an "
            "encoded model A alone (141.00s in Stage 0) is worse than the plain "
            "route-agnostic baseline — the gain only appears once routed."
        ),
    )
    return parser.parse_args()


def validate_target_encoding(args: argparse.Namespace) -> None:
    if args.target_encoding and not args.fallback_model:
        raise ValueError(
            "--target-encoding requires --fallback-model: Stage 0 (2026-08-15) found an "
            "encoded model A alone (141.00s) is worse than the plain baseline (138.53s) — "
            "the gain only shows up once routed."
        )


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


def feature_columns(with_api_feature: bool) -> list[str]:
    columns = [*NUMERIC_FEATURES, *CATEGORICAL_FEATURES]
    if with_api_feature:
        columns.append("api_target_seconds_to_arrival")
    return columns


def categorical_dtypes(frames: list[pl.DataFrame]) -> dict[str, pd.CategoricalDtype]:
    dtypes: dict[str, pd.CategoricalDtype] = {}
    for column in CATEGORICAL_FEATURES:
        values: set[Any] = set()
        for frame in frames:
            if column in frame.columns:
                values.update(
                    str(value)
                    for value in frame[column].unique().to_list()
                    if value is not None
                )
        dtypes[column] = pd.CategoricalDtype(categories=sorted(values))
    return dtypes


def to_pandas(
    frame: pl.DataFrame,
    columns: list[str],
    target_column: str,
    cat_dtypes: dict[str, pd.CategoricalDtype],
) -> tuple[pd.DataFrame, pd.Series]:
    selected = frame.select([*columns, target_column]).to_pandas()
    for column in CATEGORICAL_FEATURES:
        if column in selected.columns:
            selected[column] = selected[column].astype("string").astype(cat_dtypes[column])
    y = selected[target_column]
    x = selected.drop(columns=[target_column])
    return x, y


def validation_split(
    train: pl.DataFrame,
    target_column: str,
    columns: list[str],
    cat_dtypes: dict[str, pd.CategoricalDtype],
) -> tuple[pd.DataFrame, pd.Series, pd.DataFrame, pd.Series]:
    ordered = train.sort("snapshot_at")
    # This internal early-stopping split only tunes model iteration count. The
    # train/test split already removes rows whose label arrival crosses the
    # evaluation cutoff, so we keep all remaining train rows here.
    validation_size = max(1, int(ordered.height * 0.1))
    fit_frame = ordered.head(ordered.height - validation_size)
    validation_frame = ordered.tail(validation_size)
    if fit_frame.is_empty():
        raise ValueError("Training split is too small after validation split.")
    x_train, y_train = to_pandas(fit_frame, columns, target_column, cat_dtypes)
    x_valid, y_valid = to_pandas(validation_frame, columns, target_column, cat_dtypes)
    return x_train, y_train, x_valid, y_valid


def fit_lgbm(
    x_train: pd.DataFrame,
    y_train: pd.Series,
    x_valid: pd.DataFrame,
    y_valid: pd.Series,
    seed: int,
    categorical_feature: list[str] | str = "auto",
) -> lgb.LGBMRegressor:
    model = lgb.LGBMRegressor(
        objective="mae",
        n_estimators=2000,
        learning_rate=0.05,
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
        categorical_feature=categorical_feature,
        callbacks=[
            lgb.early_stopping(stopping_rounds=50),
            lgb.log_evaluation(period=100),
        ],
    )
    return model


def build_router_reports(
    test: pl.DataFrame,
    target_column: str,
    prediction_a: np.ndarray,
    prediction_b: np.ndarray,
    seen_mask: np.ndarray,
) -> dict[str, Any]:
    """Score model A alone, model B alone, and the routed (A on seen routes, B on
    unseen) combination. The routed report is written under the same "lightgbm"
    key gate.py already reads, so gate.py needs no changes: it transparently
    gates on the routed MAE once --fallback-model is used."""
    routed = np.where(seen_mask, prediction_a, prediction_b)
    scored_a = test.with_columns(pl.Series("prediction_model_a", prediction_a))
    scored_b = test.with_columns(pl.Series("prediction_model_b", prediction_b))
    scored_routed = test.with_columns(pl.Series("prediction_routed", routed))
    return {
        "lightgbm": evaluate_predictions(scored_routed, target_column, "prediction_routed"),
        "model_a_alone": evaluate_predictions(scored_a, target_column, "prediction_model_a"),
        "model_b_alone": evaluate_predictions(scored_b, target_column, "prediction_model_b"),
    }


def train_model(args: argparse.Namespace) -> dict[str, Any]:
    validate_target_encoding(args)

    service_dates = parse_service_dates(args.service_date)
    data_dir = load_data_dir()
    test_dates = parse_test_dates(args.test_dates) or set()
    train_dates = [item for item in service_dates if item not in test_dates]

    columns = feature_columns(args.with_api_feature)
    load_columns = (
        sorted(set(columns) | set(ENCODING_SUPPORT_COLUMNS)) if args.target_encoding else None
    )
    split = create_split(
        service_date=service_dates,
        target_name=args.target,
        sample_rows=args.sample_rows,
        per_date_sample_rows=args.per_date_sample_rows,
        test_from=args.test_from,
        test_dates=args.test_dates,
        seed=args.seed,
        data_dir=data_dir,
        columns=load_columns,
    )
    target_column = split.target_column

    if args.target_encoding:
        encoding_args = argparse.Namespace(
            target_column=target_column,
            serving_parity=True,
            route_agnostic=False,
            api_oracle=False,
            no_target_encoding=False,
            full_data_encoding=True,
            data_dir=data_dir,
            encoding_dates=train_dates,
            te_folds=5,
            te_smoothing=50.0,
            seed=args.seed,
            numeric_features=NUMERIC_FEATURES,
            encoding_specs=SERVABLE_ENCODING_SPECS,
            route_agnostic_specs=SERVABLE_ROUTE_AGNOSTIC_SPECS,
        )
        train_frame, test_frame, model_a_columns, feature_policy = build_feature_frames(
            split.train, split.test, encoding_args
        )
        print(f"[info] target-encoding: model A features={len(model_a_columns)}")
    else:
        train_frame, test_frame = split.train, split.test
        model_a_columns = columns
        feature_policy = {}

    cat_dtypes = categorical_dtypes([train_frame, test_frame])

    x_train, y_train, x_valid, y_valid = validation_split(
        train_frame, target_column, model_a_columns, cat_dtypes
    )
    x_test, _ = to_pandas(test_frame, model_a_columns, target_column, cat_dtypes)

    # 실제 train split에 1행 이상 존재한 route만. pandas_categorical(train∪test)과 달리
    # 이 값이 "모델이 실제로 학습한 route"다. serving 지원 판정·coverage gate·routing의 권위 소스.
    training_route_categories = sorted(
        str(v) for v in train_frame[CATEGORICAL_FEATURES[0]].unique().to_list() if v is not None
    )

    # A raw route_id categorical actively hurt routing in production (148.85s vs 138.53s,
    # 2026-08-14) — target-encoding drops it in favor of te_* aggregates instead.
    categorical_feature_a = "auto" if args.target_encoding else CATEGORICAL_FEATURES
    model = fit_lgbm(x_train, y_train, x_valid, y_valid, args.seed, categorical_feature_a)
    predictions = model.predict(x_test, num_iteration=model.best_iteration_)

    model_b = None
    feature_columns_b: list[str] = []
    if args.fallback_model:
        feature_columns_b = [c for c in columns if c not in CATEGORICAL_FEATURES]
        if args.target_encoding:
            # Independent of model A's frame: SERVABLE_ROUTE_AGNOSTIC_SPECS is empty
            # (Stage 0 found nothing servable for the route-agnostic side to encode), so
            # model B stays on the plain raw columns, same as without --target-encoding.
            x_train_b, y_train_b, x_valid_b, y_valid_b = validation_split(
                train_frame, target_column, feature_columns_b, cat_dtypes
            )
            x_test_b, _ = to_pandas(test_frame, feature_columns_b, target_column, cat_dtypes)
        else:
            x_train_b = x_train.drop(columns=CATEGORICAL_FEATURES)
            x_valid_b = x_valid.drop(columns=CATEGORICAL_FEATURES)
            x_test_b = x_test.drop(columns=CATEGORICAL_FEATURES)
            y_train_b, y_valid_b = y_train, y_valid
        model_b = fit_lgbm(x_train_b, y_train_b, x_valid_b, y_valid_b, args.seed, "auto")
        predictions_b = model_b.predict(x_test_b, num_iteration=model_b.best_iteration_)

        train_routes = set(training_route_categories)
        seen_mask = test_frame[CATEGORICAL_FEATURES[0]].cast(pl.Utf8).is_in(train_routes).to_numpy()
        reports = build_router_reports(
            test_frame, target_column, predictions, predictions_b, seen_mask
        )
    else:
        scored = test_frame.with_columns(pl.Series("prediction_lgbm", predictions))
        reports = {"lightgbm": evaluate_predictions(scored, target_column, "prediction_lgbm")}

    date_label = service_date_label(service_dates)
    run_id = (
        f"lgbm_{date_label}_{args.target}_"
        f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    )
    output_dir = experiment_dir(data_dir, run_id)
    model_path = output_dir / "model.txt"
    model.booster_.save_model(str(model_path))
    if model_b is not None:
        model_b.booster_.save_model(str(output_dir / "model_b.txt"))

    manifest = {
        "run_id": run_id,
        "type": "lightgbm",
        "model_version": run_id,
        "schema_version": 2,
        "service_date_range": [item.isoformat() for item in service_dates],
        "dataset_path": split.dataset_path,
        "target_policy": args.target,
        "target_column": target_column,
        "feature_list": model_a_columns,
        "leakage_columns_excluded": [
            column for column in LEAKAGE_COLUMNS if column not in model_a_columns
        ],
        "with_api_feature": args.with_api_feature,
        "router_enabled": args.fallback_model,
        "target_encoding_enabled": args.target_encoding,
        "feature_list_b": feature_columns_b,
        "sampling_policy": split.sampling_policy,
        "split_policy": split.split_policy,
        "train_rows": split.train.height,
        "test_rows": split.test.height,
        "training_route_categories": training_route_categories,
        "validation_rows": len(y_valid),
        "best_iteration": int(model.best_iteration_ or model.n_estimators),
        "metrics": reports,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit(),
        "model_file": str(model_path),
        "model_b_file": str(output_dir / "model_b.txt") if model_b is not None else None,
    }
    if args.target_encoding:
        manifest["feature_policy"] = persist_encoding_artifacts(output_dir, feature_policy)
    write_json(output_dir / "metrics.json", reports)
    write_json(output_dir / "training_manifest.json", manifest)
    print_reports(reports)
    print(f"\nmodel={model_path}")
    if model_b is not None:
        print(f"model_b={output_dir / 'model_b.txt'}")
    print(f"manifest={output_dir / 'training_manifest.json'}")
    return manifest


def main() -> int:
    args = parse_args()
    try:
        train_model(args)
    except Exception as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
