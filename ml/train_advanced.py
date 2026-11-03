"""Arrival-time model with leg-level historical travel time encoding.

Why this exists
---------------
train_compare.py's best legitimate run is LightGBM at ~138.5s MAE (route_id dropped).
The models lose almost all of that error in the long horizons: ~54s MAE in the 1-5m
bucket against ~234s in the 20-40m bucket. The trees are being asked to infer "how long
does it take to get from stop 31 to stop 58 on this route" from raw integer indices, and
absolute stop indices do not carry that meaning across routes.

This script injects that knowledge directly as a feature: the mean travel time observed
in the training period for each (route, from-stop, to-stop) leg, smoothed toward coarser
aggregates when a leg is rare. That is ordinary historical aggregation over past service
days — the same information a timetable would give you — not label leakage.

On api_target_seconds_to_arrival
--------------------------------
It is NOT usable. `api_estimated_arrival_at` lives on bus_arrival_label_event and lands
34-599 seconds after the actual arrival (median 95s), i.e. it is the last ETA published
as the bus was pulling in, not a forecast made 40 minutes earlier. Its error is flat
across every horizon bucket, which no real forecast can be. Feeding it to the model — or
quoting it as a 110.88s "API baseline" — measures an oracle. --api-oracle reproduces
those numbers for comparison only; it is never a deployable configuration.

Usage
-----
    python train_advanced.py 2026-07-28,2026-07-29,2026-07-30 --test-dates 2026-07-30 \
        --per-date-sample-rows 50000 --quick

    python train_advanced.py "$DATES" --target corrected --test-dates "$TEST" \
        --per-date-sample-rows 200000 --models ridge,lightgbm,catboost --ensemble
"""

from __future__ import annotations

import argparse
import gc
import subprocess
import sys
import time
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Callable

import numpy as np
import pandas as pd
import polars as pl

from metrics import evaluate_predictions, print_metric_block, write_json
from split import (
    HORIZON_BUCKETS,
    TARGET_COLUMNS,
    create_split,
    dataset_part_files,
    load_data_dir,
    parse_test_dates,
)
from feature_engineering import (
    API_COLUMN,
    DERIVED_FEATURES,
    ENCODING_SPECS,
    EVENT_COLUMN,
    LEAKAGE_COLUMNS,
    NON_SERVED_FEATURES,
    PRIOR_PACE_COLUMN,
    ROUTE_AGNOSTIC_SPECS,
    ROUTE_PROFILE_FEATURES,
    TARGET_MAX_SECONDS,
    TARGET_MIN_SECONDS,
    _encode_one,
    _group_stats,
    add_derived_features,
    add_hierarchical_encoding,
    build_feature_frames,
    encoding_name,
    full_data_stats,
    full_route_profile,
    persist_encoding_artifacts,
    route_profile,
)


BASE_NUMERIC_FEATURES = [
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

ALL_MODELS = ("ridge", "lightgbm", "xgboost", "catboost")


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Arrival model with leg-level historical travel time encoding."
    )
    parser.add_argument("service_date", help="Comma separated YYYY-MM-DD service dates.")
    parser.add_argument("--target", choices=sorted(TARGET_COLUMNS), default="corrected")
    parser.add_argument("--sample-rows", type=int, default=2_000_000)
    parser.add_argument("--per-date-sample-rows", type=int, default=None)
    parser.add_argument("--test-from", default="21:00")
    parser.add_argument("--test-dates", default=None)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--models", default="ridge,lightgbm")
    parser.add_argument(
        "--route-id-feature",
        action="store_true",
        help=(
            "Keep route_id as a native categorical. Off by default: routes rotate weekly "
            "and train_compare measured route_id as a 6s MAE regression."
        ),
    )
    parser.add_argument(
        "--no-target-encoding",
        action="store_true",
        help="Ablation: drop every historical-aggregate feature.",
    )
    parser.add_argument(
        "--serving-parity",
        action="store_true",
        help="Restrict raw features to what the Java feature builder sends.",
    )
    parser.add_argument("--ensemble", action="store_true")
    parser.add_argument(
        "--ensemble-models",
        default="lightgbm,catboost",
        help=(
            "Which trained models may enter the blend. Ridge is excluded by default: it "
            "scored 105s on seen routes but 307s on unseen ones, and averaging that in "
            "cost more than its seen-route accuracy was worth."
        ),
    )
    parser.add_argument(
        "--tune",
        type=int,
        default=0,
        help="Rounds of random LightGBM hyperparameter search on the validation split.",
    )
    parser.add_argument("--te-folds", type=int, default=5)
    parser.add_argument(
        "--te-smoothing",
        type=float,
        default=50.0,
        help=(
            "m-estimate weight. Higher pulls sparse legs harder toward the backoff prior. "
            "Raised from 30 once the prior became horizon-aware and therefore safe to lean on."
        ),
    )
    parser.add_argument(
        "--api-oracle",
        action="store_true",
        help=(
            "DIAGNOSTIC ONLY. Re-admit the at-arrival API ETA as a feature to reproduce "
            "the inflated historical numbers. Never deploy a model trained this way."
        ),
    )
    parser.add_argument(
        "--full-data-encoding",
        action="store_true",
        help=(
            "Compute the historical aggregates over every training row on disk instead of "
            "over the sampled frame. Sampling is a memory limit on the model matrix, not "
            "on what an aggregate can see; at 200k rows/day a route-leg averaged ~100 "
            "observations against ~4,300 over the full archive."
        ),
    )
    parser.add_argument(
        "--all-columns",
        action="store_true",
        help=(
            "Load every dataset column. Off by default: the 29 unused ones cost 258 of the "
            "421 bytes per row and decide whether a large run fits in RAM."
        ),
    )
    parser.add_argument(
        "--chunked",
        action="store_true",
        help=(
            "Train on 100%% of rows by streaming one service date at a time into a growing "
            "booster, instead of sampling a frame that fits in RAM. Chunks are whole dates, "
            "not routes: boosting needs each chunk to resemble the whole, and a per-route "
            "chunk is a systematically different slice."
        ),
    )
    parser.add_argument("--passes", type=int, default=2, help="Chunked mode: passes over the dates.")
    parser.add_argument(
        "--rounds-per-chunk", type=int, default=20, help="Chunked mode: trees added per date."
    )
    parser.add_argument(
        "--valid-days", type=int, default=7, help="Chunked mode: trailing train dates held out."
    )
    parser.add_argument(
        "--route-agnostic",
        action="store_true",
        help=(
            "Train the model that serves routes with no history: route-keyed encodings and "
            "route-profile features are dropped, leaving the section and stop keys that do "
            "transfer across routes (77-79%% hit rate on unseen-route test rows)."
        ),
    )
    parser.add_argument(
        "--router-model-a",
        default=None,
        help=(
            "Path to a model trained with the full spec set. When given, this run trains the "
            "route-agnostic model B and reports the routed combination: A for routes with "
            "training history, B for the rest. A single model cannot serve both — the one "
            "trained on dense route-keyed aggregates put 79.8%% of its gain there and lost "
            "22s of MAE on unseen routes to buy 7s on seen ones."
        ),
    )
    parser.add_argument("--quick", action="store_true", help="Small tree budget for a smoke test.")
    return parser.parse_args()


def parse_service_dates(value: str) -> list[date]:
    parsed = sorted({date.fromisoformat(item.strip()) for item in value.split(",") if item.strip()})
    if not parsed:
        raise ValueError("At least one service_date is required.")
    return parsed


def parse_models(value: str) -> list[str]:
    names = [item.strip().lower() for item in value.split(",") if item.strip()]
    unknown = [name for name in names if name not in ALL_MODELS]
    if unknown:
        raise ValueError(f"Unknown model(s): {', '.join(unknown)}. Choose from {ALL_MODELS}.")
    if not names:
        raise ValueError("At least one model is required.")
    return names


def service_date_label(service_dates: list[date]) -> str:
    if len(service_dates) == 1:
        return service_dates[0].isoformat()
    return f"{service_dates[0].isoformat()}_to_{service_dates[-1].isoformat()}"


def git_commit() -> str | None:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def experiment_dir(data_dir: Path, run_id: str) -> Path:
    path = data_dir / "experiments" / run_id
    path.mkdir(parents=True, exist_ok=True)
    return path


# --------------------------------------------------------------------------------------
# Features
# --------------------------------------------------------------------------------------
#
# route_profile, add_derived_features, DERIVED_FEATURES, encoding_name, full_data_stats,
# _encode_one, _group_stats, add_hierarchical_encoding, full_route_profile, and
# build_feature_frames now live in feature_engineering.py (imported above) so train.py can
# reuse them for the production servable-encoding path. apply_encodings/load_chunk below
# (chunked-mode only) still use the imported _encode_one/encoding_name/PRIOR_PACE_COLUMN.


# Columns the pipeline actually consumes. Everything else in the dataset is projected
# away at scan time — see split.collect_date_sample for why that matters at scale.
REQUIRED_COLUMNS = [
    "service_date",
    "snapshot_at",
    "route_id",
    "arrival_label_event_id",
    # split.sample_bucket_route anti-joins on (position_raw_id, arrival_label_event_id)
    # when a horizon bucket comes up short, so both must survive the projection.
    "position_raw_id",
    "current_section_id",
    "current_section_order",
    "current_section_distance",
    "current_full_section_distance",
    "section_progress",
    "stop_flag",
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
    "label_source",
]


def apply_encodings(
    frame: pl.DataFrame,
    specs: list[tuple[list[str], str]],
    get_stats: Callable[[list[str], str], pl.DataFrame],
    smoothing: float,
    global_pace: float,
    oof: bool,
) -> tuple[pl.DataFrame, list[str]]:
    """Attach every encoding to one frame from precomputed per-fold aggregates.

    `oof=True` gives a row total-minus-its-own-fold, which is what training rows need so
    the aggregate never contains the row's own arrival. `oof=False` gives the plain total,
    which is correct for rows the aggregate was not built from — the test week.
    """
    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    created: list[str] = []

    for index, (keys, value) in enumerate(specs):
        if any(key not in frame.columns for key in keys):
            continue
        name = encoding_name(keys, value)
        per_fold = get_stats(keys, value)
        totals = per_fold.group_by(keys).agg(
            [pl.col("_fsum").sum().alias("_sum"), pl.col("_fcount").sum().alias("_count")]
        )
        if index == 0:
            prior = pl.lit(global_pace)
        elif value == "pace":
            prior = pl.col(PRIOR_PACE_COLUMN)
        else:
            prior = pl.col(PRIOR_PACE_COLUMN) * remaining

        if oof:
            stats = (
                per_fold.join(totals, on=keys, how="left")
                .with_columns(
                    [
                        (pl.col("_sum") - pl.col("_fsum")).alias("_sum"),
                        (pl.col("_count") - pl.col("_fcount")).alias("_count"),
                    ]
                )
                .select([*keys, "_fold", "_sum", "_count"])
            )
            frame = _encode_one(frame, stats, [*keys, "_fold"], name, prior, smoothing)
        else:
            frame = _encode_one(frame, totals, keys, name, prior, smoothing)

        created.extend([name, f"{name}__n"])
        if index == 0:
            frame = frame.with_columns(pl.col(name).alias(PRIOR_PACE_COLUMN))

    for name in [c for c in created if c.startswith("te_pace__") and not c.endswith("__n")]:
        frame = frame.with_columns((pl.col(name) * remaining).alias(f"{name}__x_stops"))
        created.append(f"{name}__x_stops")
    return frame, created


def load_chunk(
    data_dir: Path,
    chunk_dates: list[date],
    target_column: str,
    columns: list[str],
    folds: int,
    seed: int,
) -> pl.DataFrame:
    """Load whole service dates with no sampling — this is the point of chunking."""
    files: list[str] = []
    for item in chunk_dates:
        files.extend(str(path) for path in dataset_part_files(data_dir, item))
    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    return (
        pl.scan_parquet(files)
        .filter(
            pl.col(target_column).is_not_null()
            & (pl.col(target_column) >= TARGET_MIN_SECONDS)
            & (pl.col(target_column) <= TARGET_MAX_SECONDS)
        )
        .select([c for c in dict.fromkeys([*columns, target_column])])
        .with_columns(
            [
                (pl.col(EVENT_COLUMN).hash(seed=seed) % folds).alias("_fold"),
                pl.when(pl.col(target_column) < 300)
                .then(pl.lit("1-5m"))
                .when(pl.col(target_column) < 600)
                .then(pl.lit("5-10m"))
                .when(pl.col(target_column) < 1200)
                .then(pl.lit("10-20m"))
                .otherwise(pl.lit("20-40m"))
                .alias("horizon_bucket"),
                (pl.col(target_column).cast(pl.Float64) / remaining).alias("_unused_pace"),
            ]
        )
        .drop("_unused_pace")
        .collect()
    )


def to_xy(frame: pl.DataFrame, features: list[str], label_column: str) -> tuple[pd.DataFrame, np.ndarray]:
    x = frame.select(features).to_pandas().astype("float32")
    y = frame[label_column].to_numpy().astype("float64")
    return x, y


def validation_split(frame: pl.DataFrame, days: int = 7) -> tuple[pl.DataFrame, pl.DataFrame]:
    """Hold out the last N service dates of train for early stopping and blend weights.

    A tail-10%-of-rows split was tried first and misled the blend badly: routes rotate
    weekly, so a validation slice drawn from the same days as the fit set shares its route
    composition, while the test week does not. Ridge scored well on that validation and
    then collapsed to 306s MAE on unseen test routes, yet still drew 29% of the blend
    weight. Splitting on whole service dates puts a route rotation between fit and
    validation, so validation finally measures the thing test measures.
    """
    if "service_date" not in frame.columns:
        ordered = frame.sort("snapshot_at")
        size = max(1, int(ordered.height * 0.1))
        return ordered.head(ordered.height - size), ordered.tail(size)

    dates = sorted(frame["service_date"].unique().to_list())
    if len(dates) <= days:
        days = max(1, len(dates) // 5)
    holdout_dates = dates[-days:]
    fit_frame = frame.filter(~pl.col("service_date").is_in(holdout_dates))
    valid_frame = frame.filter(pl.col("service_date").is_in(holdout_dates))
    if fit_frame.is_empty() or valid_frame.is_empty():
        raise ValueError("Training split is too small after the validation split.")
    print(
        f"[info] validation = last {len(holdout_dates)} service dates "
        f"({holdout_dates[0]} ~ {holdout_dates[-1]}), {valid_frame.height:,} rows"
    )
    return fit_frame, valid_frame


# --------------------------------------------------------------------------------------
# Learners
# --------------------------------------------------------------------------------------


def train_ridge(x_fit, y_fit, x_valid, y_valid, x_test, seed, quick):
    from sklearn.impute import SimpleImputer
    from sklearn.linear_model import Ridge
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler

    model = make_pipeline(SimpleImputer(strategy="median"), StandardScaler(), Ridge(alpha=1.0))
    model.fit(x_fit, y_fit)

    def save(directory: Path) -> str:
        import joblib

        path = directory / "model_ridge.joblib"
        joblib.dump(model, path)
        return str(path)

    return model.predict(x_valid), model.predict(x_test), {}, save


# Matched to train_compare.py so the only difference against its 138.51s run is the
# feature set. A larger tree budget was tried first and proved unaffordable on a shared
# 16-core box (3.5 hours without reaching 500 trees), and holding the learner fixed makes
# the comparison attributable anyway.
LEARNING_RATE = 0.05
N_ESTIMATORS = 2000
EARLY_STOPPING_ROUNDS = 50


def lightgbm_params(seed: int, quick: bool) -> dict[str, Any]:
    return {
        "objective": "mae",
        "n_estimators": 400 if quick else N_ESTIMATORS,
        "learning_rate": 0.08 if quick else LEARNING_RATE,
        "num_leaves": 63 if quick else 64,
        "subsample": 0.9,
        "subsample_freq": 1,
        "colsample_bytree": 0.9,
        "random_state": seed,
        "n_jobs": -1,
        "verbose": -1,
    }


def train_lightgbm(x_fit, y_fit, x_valid, y_valid, x_test, seed, quick, params=None):
    import lightgbm as lgb

    model = lgb.LGBMRegressor(**(params or lightgbm_params(seed, quick)))
    model.fit(
        x_fit,
        y_fit,
        eval_set=[(x_valid, y_valid)],
        eval_metric="mae",
        callbacks=[
            lgb.early_stopping(stopping_rounds=EARLY_STOPPING_ROUNDS, verbose=False),
            lgb.log_evaluation(period=50),
        ],
    )
    best = model.best_iteration_
    info = {
        "best_iteration": int(best or model.n_estimators),
        "feature_importance": dict(
            sorted(
                zip(x_fit.columns, (int(v) for v in model.booster_.feature_importance("gain"))),
                key=lambda item: item[1],
                reverse=True,
            )[:25]
        ),
    }

    def save(directory: Path) -> str:
        path = directory / "model_lightgbm.txt"
        model.booster_.save_model(str(path))
        return str(path)

    return model.predict(x_valid, num_iteration=best), model.predict(x_test, num_iteration=best), info, save


def train_xgboost(x_fit, y_fit, x_valid, y_valid, x_test, seed, quick):
    import xgboost as xgb

    model = xgb.XGBRegressor(
        # L1 counterpart of LightGBM objective="mae"; needs XGBoost >= 2.0.
        objective="reg:absoluteerror",
        eval_metric="mae",
        n_estimators=400 if quick else 4000,
        learning_rate=0.08 if quick else 0.03,
        max_depth=0,
        grow_policy="lossguide",
        max_leaves=63 if quick else 255,
        min_child_weight=40,
        subsample=0.85,
        colsample_bytree=0.8,
        reg_lambda=1.0,
        early_stopping_rounds=100,
        random_state=seed,
        n_jobs=-1,
        tree_method="hist",
    )
    model.fit(x_fit, y_fit, eval_set=[(x_valid, y_valid)], verbose=500)
    info = {"best_iteration": int(getattr(model, "best_iteration", 0) or model.n_estimators)}

    def save(directory: Path) -> str:
        path = directory / "model_xgboost.json"
        model.save_model(str(path))
        return str(path)

    return model.predict(x_valid), model.predict(x_test), info, save


def train_catboost(x_fit, y_fit, x_valid, y_valid, x_test, seed, quick):
    from catboost import CatBoostRegressor

    model = CatBoostRegressor(
        loss_function="MAE",
        eval_metric="MAE",
        # CatBoost hit the 2000 cap with early stopping never firing (best_iteration=1999),
        # so it was still improving when it ran out of budget. LightGBM converged at 1302
        # under the same schedule, so the extra headroom is CatBoost-specific.
        iterations=400 if quick else 6000,
        learning_rate=0.08 if quick else LEARNING_RATE,
        # Symmetric trees: depth 6 is the same 64-leaf budget as LightGBM num_leaves=64.
        depth=6,
        random_seed=seed,
        early_stopping_rounds=EARLY_STOPPING_ROUNDS,
        thread_count=-1,
        verbose=100,
        allow_writing_files=False,
    )
    model.fit(x_fit, y_fit, eval_set=(x_valid, y_valid))
    info = {"best_iteration": int(model.get_best_iteration() or model.tree_count_)}

    def save(directory: Path) -> str:
        path = directory / "model_catboost.cbm"
        model.save_model(str(path))
        return str(path)

    return model.predict(x_valid), model.predict(x_test), info, save


def tune_lightgbm(x_fit, y_fit, x_valid, y_valid, rounds: int, seed: int) -> dict[str, Any]:
    """Random search on the chronological validation split.

    Random rather than grid because only a couple of these knobs move MAE, and a grid
    spends most of its budget varying the ones that do not.
    """
    import lightgbm as lgb

    rng = np.random.default_rng(seed)
    best_params: dict[str, Any] | None = None
    best_mae = float("inf")

    for index in range(rounds):
        params = {
            "objective": "mae",
            "n_estimators": 6000,
            "learning_rate": float(rng.choice([0.02, 0.03, 0.05])),
            "num_leaves": int(rng.choice([127, 255, 511, 1023])),
            "min_child_samples": int(rng.choice([20, 40, 80, 160])),
            "subsample": float(rng.choice([0.7, 0.85, 1.0])),
            "subsample_freq": 1,
            "colsample_bytree": float(rng.choice([0.6, 0.8, 1.0])),
            "reg_lambda": float(rng.choice([0.0, 1.0, 5.0, 20.0])),
            "random_state": seed,
            "n_jobs": -1,
            "verbose": -1,
        }
        model = lgb.LGBMRegressor(**params)
        model.fit(
            x_fit,
            y_fit,
            eval_set=[(x_valid, y_valid)],
            eval_metric="mae",
            callbacks=[lgb.early_stopping(stopping_rounds=100, verbose=False)],
        )
        mae = float(np.abs(model.predict(x_valid, num_iteration=model.best_iteration_) - y_valid).mean())
        marker = ""
        if mae < best_mae:
            best_mae, best_params = mae, params
            marker = "  <- best"
        print(f"[tune] {index + 1}/{rounds} valid_mae={mae:.3f}{marker}", flush=True)

    if best_params is None:
        raise ValueError("Tuning produced no candidate.")
    print(f"[tune] best validation MAE={best_mae:.3f}")
    return best_params


LEARNERS: dict[str, Callable[..., Any]] = {
    "ridge": train_ridge,
    "lightgbm": train_lightgbm,
    "xgboost": train_xgboost,
    "catboost": train_catboost,
}


# --------------------------------------------------------------------------------------
# Evaluation
# --------------------------------------------------------------------------------------


def naive_pace_baseline(train: pl.DataFrame, test: pl.DataFrame, target_column: str) -> np.ndarray:
    """Non-ML reference: train-median seconds-per-stop scaled by remaining stops.

    Without this the summary has no honest floor to compare against, since the API column
    is an at-arrival observation rather than a forecast.
    """
    paced = train.filter(pl.col("remaining_stop_count") > 0)
    median_pace = float(
        (paced[target_column] / paced["remaining_stop_count"]).median() or 120.0
    ) if not paced.is_empty() else 120.0
    global_median = float(train[target_column].median() or 600.0)
    remaining = test["remaining_stop_count"].cast(pl.Float64).to_numpy()
    prediction = np.where(remaining > 0, remaining * median_pace, global_median)
    return np.clip(prediction, TARGET_MIN_SECONDS, TARGET_MAX_SECONDS)


def seen_route_reports(test, train_routes, target_column, prediction_column) -> dict[str, Any]:
    """Split the metric by whether the route had training history.

    Weekly rotation means the headline number mixes two populations; reporting them
    together hides whether a change helped the model or just drew an easier test week.

    Takes the route list rather than the train frame so the caller can release that frame
    before training starts.
    """
    seen = test.filter(pl.col("route_id").is_in(train_routes))
    unseen = test.filter(~pl.col("route_id").is_in(train_routes))
    result: dict[str, Any] = {
        "seen_rows": seen.height,
        "unseen_rows": unseen.height,
        "seen_share": float(seen.height / test.height) if test.height else float("nan"),
    }
    if not seen.is_empty():
        result["seen"] = evaluate_predictions(seen, target_column, prediction_column)
    if not unseen.is_empty():
        result["unseen"] = evaluate_predictions(unseen, target_column, prediction_column)
    return result


def print_summary(rows: list[dict[str, Any]], reference_name: str) -> None:
    print("\n" + "=" * 96)
    print("SUMMARY (test set MAE in seconds, lower is better)")
    print("=" * 96)
    header = (
        f"{'model':<22}{'MAE':>9}{'RMSE':>9}{'p90':>9}"
        f"{'1-5m':>9}{'5-10m':>9}{'10-20m':>9}{'20-40m':>9}{'unseen':>9}"
    )
    print(header)
    print("-" * len(header))
    for row in rows:
        print(
            f"{row['name']:<22}{row['mae']:>9.2f}{row['rmse']:>9.2f}{row['p90']:>9.2f}"
            f"{row.get('h1', float('nan')):>9.1f}{row.get('h2', float('nan')):>9.1f}"
            f"{row.get('h3', float('nan')):>9.1f}{row.get('h4', float('nan')):>9.1f}"
            f"{row.get('unseen_mae', float('nan')):>9.1f}"
        )
    reference = next((row for row in rows if row["name"] == reference_name), None)
    candidates = [row for row in rows if row["name"] != reference_name]
    if reference and candidates:
        best = min(candidates, key=lambda item: item["mae"])
        gain = 100.0 * (reference["mae"] - best["mae"]) / reference["mae"]
        print("-" * len(header))
        print(
            f"best={best['name']} {best['mae']:.2f}s  vs  {reference_name} "
            f"{reference['mae']:.2f}s  ->  {gain:+.1f}%"
        )


def summary_row(name: str, report: dict[str, Any], history: dict[str, Any]) -> dict[str, Any]:
    horizon = report.get("by_horizon", {})
    return {
        "name": name,
        "mae": report["overall"]["mae"],
        "rmse": report["overall"]["rmse"],
        "p90": report["overall"]["p90_abs_error"],
        "h1": horizon.get("1-5m", {}).get("mae", float("nan")),
        "h2": horizon.get("5-10m", {}).get("mae", float("nan")),
        "h3": horizon.get("10-20m", {}).get("mae", float("nan")),
        "h4": horizon.get("20-40m", {}).get("mae", float("nan")),
        "unseen_mae": history.get("unseen", {}).get("overall", {}).get("mae", float("nan")),
    }


# --------------------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------------------


def run_chunked(args: argparse.Namespace) -> dict[str, Any]:
    """Train on every row by streaming whole service dates through one growing booster.

    Boosting adds trees on top of what earlier trees already explain, so each chunk has to
    look like the whole. Service dates satisfy that — every date carries all routes across
    all hours. Splitting by route would not: each chunk would be a different city corner,
    and the booster would spend its budget undoing the previous route's fit. Chunk order is
    shuffled and multiple passes are made so no single date owns the final trees.
    """
    import lightgbm as lgb

    service_dates = parse_service_dates(args.service_date)
    data_dir = load_data_dir()
    test_dates = sorted(parse_test_dates(args.test_dates) or set())
    train_dates = [item for item in service_dates if item not in set(test_dates)]
    args.data_dir = data_dir
    args.encoding_dates = train_dates
    target_column = TARGET_COLUMNS[args.target]
    args.target_column = target_column

    valid_dates = train_dates[-args.valid_days :]
    fit_dates = train_dates[: -args.valid_days]
    print(f"[chunk] fit dates={len(fit_dates)}  valid dates={len(valid_dates)}  test dates={len(test_dates)}")

    cache: dict[tuple[str, str], pl.DataFrame] = {}

    def get_stats(keys: list[str], value: str) -> pl.DataFrame:
        key = ("+".join(keys), value)
        if key not in cache:
            started = time.perf_counter()
            cache[key] = full_data_stats(
                data_dir, train_dates, target_column, keys, value, args.te_folds, args.seed
            )
            print(
                f"[chunk] stats {key} -> {cache[key].height:,} groups "
                f"in {time.perf_counter() - started:.0f}s",
                flush=True,
            )
        return cache[key]

    profile, global_pace = full_route_profile(data_dir, train_dates, target_column)
    print(f"[chunk] route profile: {profile.height} routes, global pace {global_pace:.2f} s/stop")

    columns = REQUIRED_COLUMNS

    def prepare(chunk_dates: list[date], oof: bool) -> tuple[pd.DataFrame, np.ndarray, pl.DataFrame]:
        frame = load_chunk(data_dir, chunk_dates, target_column, columns, args.te_folds, args.seed)
        frame = add_derived_features(frame, profile)
        frame, encoded = apply_encodings(
            frame, ENCODING_SPECS, get_stats, args.te_smoothing, global_pace, oof
        )
        numeric = [c for c in BASE_NUMERIC_FEATURES if c in frame.columns]
        numeric += [c for c in NON_SERVED_FEATURES if c in frame.columns]
        feats = numeric + [c for c in DERIVED_FEATURES if c in frame.columns] + encoded
        feats = [c for c in feats if frame[c].null_count() < frame.height]
        x = frame.select(feats).to_pandas().astype("float32")
        y = frame[target_column].to_numpy().astype("float64")
        keep = frame.select(
            [c for c in ("route_id", "horizon_bucket", target_column) if c in frame.columns]
        )
        del frame
        gc.collect()
        return x, y, keep

    x_valid, y_valid, _ = prepare(valid_dates, oof=True)
    print(f"[chunk] validation ready: {len(y_valid):,} rows, {x_valid.shape[1]} features", flush=True)
    features = list(x_valid.columns)

    params = lightgbm_params(args.seed, args.quick)
    rounds_per_chunk = args.rounds_per_chunk
    params.pop("n_estimators", None)

    booster: Any = None
    rng = np.random.default_rng(args.seed)
    order: list[date] = []
    for _ in range(args.passes):
        shuffled = list(fit_dates)
        rng.shuffle(shuffled)
        order.extend(shuffled)

    total_rows = 0
    for index, day in enumerate(order, 1):
        started = time.perf_counter()
        x, y, _ = prepare([day], oof=True)
        total_rows += len(y)
        booster = lgb.train(
            params,
            lgb.Dataset(x[features], y),
            num_boost_round=rounds_per_chunk,
            init_model=booster,
            keep_training_booster=True,
        )
        del x, y
        gc.collect()
        if index % 5 == 0 or index == len(order):
            mae = float(np.abs(booster.predict(x_valid) - y_valid).mean())
            print(
                f"[chunk] {index}/{len(order)} {day} trees={booster.num_trees()} "
                f"rows_seen={total_rows:,} valid_mae={mae:.2f} "
                f"({time.perf_counter() - started:.0f}s/chunk)",
                flush=True,
            )

    print(f"[chunk] training done: {booster.num_trees()} trees over {total_rows:,} rows")

    x_test, y_test, test_keep = prepare(test_dates, oof=False)
    prediction = np.clip(booster.predict(x_test[features]), TARGET_MIN_SECONDS, TARGET_MAX_SECONDS)
    del x_test
    gc.collect()

    train_routes = profile["route_id"].unique().to_list()
    scored = test_keep.with_columns(pl.Series("prediction_lightgbm_chunked", prediction))
    report = evaluate_predictions(scored, target_column, "prediction_lightgbm_chunked")
    history = seen_route_reports(scored, train_routes, target_column, "prediction_lightgbm_chunked")
    print_metric_block("lightgbm (chunked, full data)", report)

    print_summary([summary_row("lightgbm_chunked", report, history)], "lightgbm_chunked")

    output_dir = experiment_dir(
        data_dir,
        f"chunked_{service_date_label(service_dates)}_{args.target}_"
        f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}",
    )
    model_path = output_dir / "model_lightgbm_chunked.txt"
    booster.save_model(str(model_path))
    reports = {"lightgbm_chunked": {**report, "route_history": history}}
    manifest = {
        "run_id": output_dir.name,
        "type": "chunked",
        "mode": "date-chunked incremental boosting over 100% of rows",
        "service_date_range": [item.isoformat() for item in service_dates],
        "target_column": target_column,
        "feature_list": features,
        "passes": args.passes,
        "rounds_per_chunk": rounds_per_chunk,
        "chunks": len(order),
        "trees": booster.num_trees(),
        "rows_seen": total_rows,
        "valid_rows": int(len(y_valid)),
        "test_rows": int(len(y_test)),
        "metrics": reports,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit(),
        "model_file": str(model_path),
    }
    write_json(output_dir / "metrics.json", reports)
    write_json(output_dir / "training_manifest.json", manifest)
    print(f"\nmanifest={output_dir / 'training_manifest.json'}")
    return manifest


def run(args: argparse.Namespace) -> dict[str, Any]:
    service_dates = parse_service_dates(args.service_date)
    models = parse_models(args.models)
    data_dir = load_data_dir()

    print(f"[info] data_dir={data_dir}")
    test_dates = parse_test_dates(args.test_dates) or set()
    args.data_dir = data_dir
    # Aggregates must come from the training period only; a test-week row in the source
    # would put the answer into its own feature.
    args.encoding_dates = [item for item in service_dates if item not in test_dates]

    columns = None if args.all_columns else REQUIRED_COLUMNS
    split = create_split(
        service_date=service_dates,
        target_name=args.target,
        sample_rows=args.sample_rows,
        per_date_sample_rows=args.per_date_sample_rows,
        test_from=args.test_from,
        test_dates=args.test_dates,
        seed=args.seed,
        data_dir=data_dir,
        columns=columns,
    )
    args.target_column = split.target_column
    target_column = split.target_column
    # build_feature_frames (feature_engineering.py) takes the numeric base + encoding
    # specs as args attributes rather than module-level constants, so train.py can pass
    # its own NUMERIC_FEATURES + SERVABLE_ENCODING_SPECS through the same function.
    args.numeric_features = BASE_NUMERIC_FEATURES
    args.encoding_specs = ENCODING_SPECS
    args.route_agnostic_specs = ROUTE_AGNOSTIC_SPECS
    print(f"[info] train_rows={split.train.height:,} test_rows={split.test.height:,} target={target_column}")
    if args.api_oracle:
        print(
            "[WARN] --api-oracle: api_target_seconds_to_arrival is an at-arrival observation "
            "(34-599s after the real arrival). These numbers are diagnostic only."
        )

    train, test, features, feature_policy = build_feature_frames(split.train, split.test, args)
    print(f"[info] features={len(features)}")
    if feature_policy.get("dropped_all_null_features"):
        print(f"[info] dropped all-null: {feature_policy['dropped_all_null_features']}")
    if not args.serving_parity:
        extra = [column for column in NON_SERVED_FEATURES if column in features]
        if extra:
            print(f"[warn] {extra} are not sent by the Java feature builder (offline-only).")

    fit_frame, valid_frame = validation_split(train)
    x_fit, y_fit = to_xy(fit_frame, features, target_column)
    x_valid, y_valid = to_xy(valid_frame, features, target_column)
    x_test, _ = to_xy(test, features, target_column)

    # Everything the polars frames are still needed for, extracted before they are freed.
    # At 48M rows the encoded train frame is ~50 GB, and holding it through training on
    # top of the float32 matrices is what pushed the previous attempt to 88 GB resident
    # with 26 GB left. Only the route list and the test scoring columns survive.
    naive = naive_pace_baseline(train, test, target_column)
    train_routes = train["route_id"].unique().to_list()
    scoring_columns = [
        column
        for column in ("route_id", "horizon_bucket", "label_source", target_column)
        if column in test.columns
    ]
    valid_buckets = valid_frame["horizon_bucket"].to_numpy()
    test = test.select(scoring_columns)
    del train, fit_frame, valid_frame
    gc.collect()
    print(f"[info] released source frames; {len(train_routes)} train routes retained")

    reports: dict[str, Any] = {}
    summary_rows: list[dict[str, Any]] = []

    scored = test.with_columns(pl.Series("prediction_naive_pace", naive))
    report = evaluate_predictions(scored, target_column, "prediction_naive_pace")
    history = seen_route_reports(scored, train_routes, target_column, "prediction_naive_pace")
    reports["naive_pace"] = {**report, "route_history": history}
    print_metric_block("naive_pace (median seconds/stop x remaining stops)", report)
    summary_rows.append(summary_row("naive_pace", report, history))

    tuned_params: dict[str, Any] | None = None
    if args.tune > 0:
        print(f"\n[tune] random search over {args.tune} LightGBM configurations")
        tuned_params = tune_lightgbm(x_fit, y_fit, x_valid, y_valid, args.tune, args.seed)

    output_dir = experiment_dir(
        data_dir,
        f"advanced_{service_date_label(service_dates)}_{args.target}_"
        f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}",
    )

    valid_predictions: dict[str, np.ndarray] = {}
    test_predictions: dict[str, np.ndarray] = {}
    model_info: dict[str, Any] = {}
    model_files: dict[str, str] = {}

    for name in models:
        print(f"\n[train] {name}", flush=True)
        started = time.perf_counter()
        try:
            if name == "lightgbm":
                valid_raw, test_raw, info, save = train_lightgbm(
                    x_fit, y_fit, x_valid, y_valid, x_test, args.seed, args.quick, tuned_params
                )
            else:
                valid_raw, test_raw, info, save = LEARNERS[name](
                    x_fit, y_fit, x_valid, y_valid, x_test, args.seed, args.quick
                )
        except ImportError as exc:
            print(f"[warn] skipping {name}: {exc}")
            continue

        elapsed = time.perf_counter() - started
        valid_predictions[name] = np.clip(valid_raw, TARGET_MIN_SECONDS, TARGET_MAX_SECONDS)
        test_predictions[name] = np.clip(test_raw, TARGET_MIN_SECONDS, TARGET_MAX_SECONDS)
        model_info[name] = {**info, "train_seconds": round(elapsed, 1)}
        model_files[name] = save(output_dir)
        print(f"[train] {name} done in {elapsed:.1f}s")

    members = [
        name for name in parse_models(args.ensemble_models) if name in test_predictions
    ]
    if args.ensemble and len(members) > 1:
        # Weights are fitted per horizon bucket, not globally. The models disagree about
        # where they are strong — LightGBM owned the 20-40m bucket at 190s while the
        # global blend dragged it to 205s — so one set of weights across all horizons
        # gives away the gains of whichever model is best at each range.
        test_buckets = test["horizon_bucket"].to_numpy()
        blended = np.zeros(len(test_buckets), dtype="float64")
        bucket_weights: dict[str, dict[str, float]] = {}

        for bucket in HORIZON_BUCKETS:
            valid_mask = valid_buckets == bucket
            test_mask = test_buckets == bucket
            if not valid_mask.any() or not test_mask.any():
                continue
            raw = {
                name: 1.0
                / max(
                    float(np.abs(valid_predictions[name][valid_mask] - y_valid[valid_mask]).mean()),
                    1e-6,
                )
                for name in members
            }
            total = sum(raw.values())
            weights = {name: value / total for name, value in raw.items()}
            bucket_weights[bucket] = weights
            blended[test_mask] = sum(
                test_predictions[name][test_mask] * weight for name, weight in weights.items()
            )
            print(f"[ensemble] {bucket:>7}: { {k: round(v, 3) for k, v in weights.items()} }")

        # Any bucket absent from validation falls back to an unweighted mean rather than
        # to zero, which would otherwise silently score as a 60s prediction after clipping.
        uncovered = blended == 0.0
        if uncovered.any():
            print(f"[ensemble] {int(uncovered.sum()):,} rows had no bucket weights; using plain mean")
            blended[uncovered] = np.mean(
                [test_predictions[name][uncovered] for name in members], axis=0
            )

        test_predictions["ensemble"] = np.clip(blended, TARGET_MIN_SECONDS, TARGET_MAX_SECONDS)
        model_info["ensemble"] = {"members": members, "weights_by_horizon": bucket_weights}
        excluded = [name for name in test_predictions if name not in members and name != "ensemble"]
        if excluded:
            print(f"[ensemble] excluded from blend: {excluded}")

    for name, prediction in test_predictions.items():
        column = f"prediction_{name}"
        scored = test.with_columns(pl.Series(column, prediction))
        report = evaluate_predictions(scored, target_column, column)
        history = seen_route_reports(scored, train_routes, target_column, column)
        reports[name] = {**report, "route_history": history}
        print_metric_block(name, report)
        summary_rows.append(summary_row(name, report, history))

    if args.router_model_a:
        import lightgbm as lgb

        # Model A is rebuilt with the full spec set in this same process, so its feature
        # matrix is constructed by the identical code path that produced the saved model.
        print(f"\n[router] loading model A from {args.router_model_a}")
        booster_a = lgb.Booster(model_file=args.router_model_a)
        agnostic = args.route_agnostic
        args.route_agnostic = False
        train_a, test_a, features_a, _ = build_feature_frames(split.train, split.test, args)
        args.route_agnostic = agnostic
        expected = booster_a.feature_name()
        missing = [column for column in expected if column not in features_a]
        if missing:
            raise ValueError(f"model A expects features this run did not build: {missing[:5]}")
        x_test_a = test_a.select(expected).to_pandas().astype("float32")
        prediction_a = np.clip(
            booster_a.predict(x_test_a), TARGET_MIN_SECONDS, TARGET_MAX_SECONDS
        )
        del train_a, test_a, x_test_a
        gc.collect()

        seen_mask = test["route_id"].is_in(train_routes).to_numpy()
        print(
            f"[router] seen rows={int(seen_mask.sum()):,} "
            f"unseen rows={int((~seen_mask).sum()):,}"
        )
        for name in [n for n in ("lightgbm", "ensemble") if n in test_predictions]:
            routed = np.where(seen_mask, prediction_a, test_predictions[name])
            column = f"prediction_routed_{name}"
            scored = test.with_columns(pl.Series(column, routed))
            report = evaluate_predictions(scored, target_column, column)
            history = seen_route_reports(scored, train_routes, target_column, column)
            reports[f"routed_{name}"] = {**report, "route_history": history}
            print_metric_block(f"routed (A on seen, {name} on unseen)", report)
            summary_rows.append(summary_row(f"routed_{name}", report, history))

        scored_a = test.with_columns(pl.Series("prediction_model_a", prediction_a))
        report_a = evaluate_predictions(scored_a, target_column, "prediction_model_a")
        history_a = seen_route_reports(scored_a, train_routes, target_column, "prediction_model_a")
        reports["model_a_alone"] = {**report_a, "route_history": history_a}
        summary_rows.append(summary_row("model_a_alone", report_a, history_a))

    print_summary(summary_rows, "naive_pace")

    manifest = {
        "run_id": output_dir.name,
        "type": "advanced",
        "schema_version": 2,
        "service_date_range": [item.isoformat() for item in service_dates],
        "dataset_path": split.dataset_path,
        "target_policy": args.target,
        "target_column": target_column,
        "models": list(test_predictions),
        "feature_list": features,
        "serving_parity": args.serving_parity,
        "api_oracle": args.api_oracle,
        "leakage_columns_excluded": [c for c in LEAKAGE_COLUMNS if c not in features],
        "tuned_params": tuned_params,
        "sampling_policy": split.sampling_policy,
        "split_policy": split.split_policy,
        "train_rows": split.train.height,
        "test_rows": split.test.height,
        "model_info": model_info,
        "model_files": model_files,
        "metrics": reports,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit(),
    }
    # feature_policy holds live pl.DataFrame values (route_profile, per-spec encoding
    # tables) whenever target encoding ran — json.dump can't serialize those, so persist
    # them to parquet and swap in file paths before the manifest gets written.
    manifest["feature_policy"] = persist_encoding_artifacts(output_dir, feature_policy)
    write_json(output_dir / "metrics.json", reports)
    write_json(output_dir / "training_manifest.json", manifest)
    print(f"\nmanifest={output_dir / 'training_manifest.json'}")
    return manifest


def main() -> int:
    args = parse_args()
    try:
        run_chunked(args) if args.chunked else run(args)
    except Exception as exc:  # noqa: BLE001 - operator needs the trace
        import traceback

        traceback.print_exc()
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
