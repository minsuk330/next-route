"""Route-keyed derived features and hierarchical out-of-fold target encoding.

Extracted from train_advanced.py (2026-08-15) so train.py can reuse the exact same
encoding machinery that produced the 119.37s research result, instead of re-deriving it.
See docs/api-eta-leakage.md section 4.1 for the encoding's origin and rationale.
"""

from __future__ import annotations

import time
from datetime import date
from pathlib import Path
from typing import Any, Callable

import numpy as np
import polars as pl

from split import dataset_part_files

EVENT_COLUMN = "arrival_label_event_id"
API_COLUMN = "api_target_seconds_to_arrival"

# Range enforced by split.filter_for_target; a prediction outside it is known-wrong.
TARGET_MIN_SECONDS = 60
TARGET_MAX_SECONDS = 2400

# Column holding the per-row backoff prior in seconds-per-stop, produced by the first
# encoding spec and consumed by all the rest.
PRIOR_PACE_COLUMN = "_prior_pace"

# Columns that describe the label or the future — never admissible as features.
LEAKAGE_COLUMNS = [
    "api_estimated_arrival_at",
    "corrected_arrival_at",
    "label_arrival_at",
    "api_target_seconds_to_arrival",
    "corrected_target_seconds_to_arrival",
    "label_target_seconds_to_arrival",
    "label_source",
    "label_confidence",
    "correction_source",
    "correction_confidence",
]

# In the dataset but not sent by the Java MlFeatureVectorBuilder.
NON_SERVED_FEATURES = ["snow_depth_cm", "weather_severity", "stop_flag"]

# (keys, value) pairs for smoothed out-of-fold encoding, in backoff order. ORDER MATTERS:
# the first entry must be the route-level pace, since every later encoding uses it as its
# prior (see add_hierarchical_encoding).
ENCODING_SPECS: list[tuple[list[str], str]] = [
    (["route_id"], "pace"),
    (["route_id", "hour_of_day"], "pace"),
    (["current_section_id"], "pace"),
    (["target_stop_id"], "pace"),
    (["route_id", "current_section_order", "target_seq"], "pace"),
    (["route_id", "current_section_order", "target_seq"], "target"),
    (["current_section_id", "target_section_id"], "target"),
    (["route_id", "target_seq"], "target"),
]

# Route-agnostic counterpart: every route-keyed entry is gone.
ROUTE_AGNOSTIC_SPECS: list[tuple[list[str], str]] = [
    (["current_section_id"], "pace"),
    (["target_stop_id"], "pace"),
    (["target_section_id"], "pace"),
    (["current_section_id", "hour_of_day"], "pace"),
    (["target_stop_id", "hour_of_day"], "pace"),
    (["current_section_id", "target_section_id"], "target"),
]

# Deployable-today subset of ENCODING_SPECS: keys only on fields already in the /predict
# feature contract (route_id, hour_of_day, current_section_order, target_seq — all in
# train.py's NUMERIC_FEATURES/CATEGORICAL_FEATURES). The 3 specs dropped from
# ENCODING_SPECS key on current_section_id/target_stop_id/target_section_id, which the
# Java MlFeatureVectorBuilder does not send today; serving those would need a cross-repo
# feature-contract change. Validated 2026-08-15 (Stage 0 of the production port): routed
# MAE 117.67s on the same 56-day/holdout window that gives the production baseline
# 138.51s/138.53s and the (non-servable) research routing result 119.37s — so this subset
# alone, with zero Java changes, beats both.
SERVABLE_ENCODING_SPECS: list[tuple[list[str], str]] = [
    (["route_id"], "pace"),
    (["route_id", "hour_of_day"], "pace"),
    (["route_id", "current_section_order", "target_seq"], "pace"),
    (["route_id", "current_section_order", "target_seq"], "target"),
    (["route_id", "target_seq"], "target"),
]

# Every servable route-agnostic key is one of the 3 dropped above, so there is nothing
# deployable left for a route-agnostic encoding: model B gets none (Stage 0 finding).
SERVABLE_ROUTE_AGNOSTIC_SPECS: list[tuple[list[str], str]] = []

# Derived features computed from the train-only route profile. Null for a route with no
# history, so the route-agnostic model drops them.
ROUTE_PROFILE_FEATURES = [
    "route_total_stops",
    "route_mean_congestion",
    "route_mean_section_distance",
    "route_train_rows",
    "current_position_ratio",
    "target_position_ratio",
    "remaining_stop_ratio",
    "congestion_vs_route",
]

DERIVED_FEATURES = [
    "current_position_ratio",
    "target_position_ratio",
    "remaining_stop_ratio",
    "congestion_vs_route",
    "approx_remaining_distance",
    "minute_sin",
    "minute_cos",
    "dow_sin",
    "dow_cos",
    "route_total_stops",
    "route_mean_congestion",
    "route_mean_section_distance",
    "route_train_rows",
]


def encoding_name(keys: list[str], value: str) -> str:
    return f"te_{value}__" + "_".join(keys)


def route_profile(train: pl.DataFrame) -> pl.DataFrame:
    """Per-route structural statistics, train-only so no test information enters."""
    return train.group_by("route_id").agg(
        [
            pl.max_horizontal(
                pl.col("current_section_order").max(), pl.col("target_seq").max()
            ).alias("route_total_stops"),
            pl.col("congestion").mean().alias("route_mean_congestion"),
            pl.col("current_full_section_distance").mean().alias("route_mean_section_distance"),
            pl.len().alias("route_train_rows"),
        ]
    )


def full_route_profile(
    data_dir: Path, train_dates: list[date], target_column: str
) -> tuple[pl.DataFrame, float]:
    """Route profile and the global pace prior, streamed over every training row."""
    files: list[str] = []
    for item in train_dates:
        files.extend(str(path) for path in dataset_part_files(data_dir, item))

    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    scan = pl.scan_parquet(files).filter(
        pl.col(target_column).is_not_null()
        & (pl.col(target_column) >= TARGET_MIN_SECONDS)
        & (pl.col(target_column) <= TARGET_MAX_SECONDS)
    )
    profile = (
        scan.group_by("route_id")
        .agg(
            [
                pl.max_horizontal(
                    pl.col("current_section_order").max(), pl.col("target_seq").max()
                ).alias("route_total_stops"),
                pl.col("congestion").mean().alias("route_mean_congestion"),
                pl.col("current_full_section_distance").mean().alias("route_mean_section_distance"),
                pl.len().alias("route_train_rows"),
            ]
        )
        .collect(engine="streaming")
    )
    pace = (
        scan.select(
            (
                pl.col(target_column).cast(pl.Float64)
                / pl.when(remaining > 0).then(remaining).otherwise(pl.lit(1.0))
            ).alias("_p")
        )
        .select(pl.col("_p").median())
        .collect(engine="streaming")
        .item()
    )
    return profile, float(pace or 120.0)


def add_derived_features(frame: pl.DataFrame, profile: pl.DataFrame) -> pl.DataFrame:
    joined = frame.join(profile, on="route_id", how="left")

    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    total_stops = pl.col("route_total_stops").cast(pl.Float64)
    safe_total = pl.when(total_stops > 0).then(total_stops).otherwise(None)
    minute = pl.col("minute_of_day").cast(pl.Float64)

    return joined.with_columns(
        [
            # Route-relative position transfers across routes with different stop counts;
            # the raw index does not.
            (pl.col("current_section_order") / safe_total).alias("current_position_ratio"),
            (pl.col("target_seq") / safe_total).alias("target_position_ratio"),
            (remaining / safe_total).alias("remaining_stop_ratio"),
            (pl.col("congestion") - pl.col("route_mean_congestion")).alias("congestion_vs_route"),
            (
                pl.col("current_full_section_distance") * remaining
            ).alias("approx_remaining_distance"),
            # Cyclical time: raw minute_of_day makes 23:59 and 00:01 maximally distant.
            (minute / 1440.0 * 2 * np.pi).sin().alias("minute_sin"),
            (minute / 1440.0 * 2 * np.pi).cos().alias("minute_cos"),
            (pl.col("day_of_week").cast(pl.Float64) / 7.0 * 2 * np.pi).sin().alias("dow_sin"),
            (pl.col("day_of_week").cast(pl.Float64) / 7.0 * 2 * np.pi).cos().alias("dow_cos"),
        ]
    )


def full_data_stats(
    data_dir: Path,
    train_dates: list[date],
    target_column: str,
    keys: list[str],
    value: str,
    folds: int,
    seed: int,
) -> pl.DataFrame:
    """Aggregate one encoding over every training row on disk, not just a sampled frame.

    Returns per (keys, fold) sums so the caller can form an out-of-fold aggregate by
    subtracting a row's own fold from the total.
    """
    files: list[str] = []
    for item in train_dates:
        files.extend(str(path) for path in dataset_part_files(data_dir, item))

    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    value_expr = (
        pl.col(target_column).cast(pl.Float64)
        if value == "target"
        else pl.col(target_column).cast(pl.Float64)
        / pl.when(remaining > 0).then(remaining).otherwise(pl.lit(1.0))
    )

    frame = (
        pl.scan_parquet(files)
        .filter(
            pl.col(target_column).is_not_null()
            & (pl.col(target_column) >= TARGET_MIN_SECONDS)
            & (pl.col(target_column) <= TARGET_MAX_SECONDS)
        )
        .with_columns(
            [
                value_expr.alias("_v"),
                (pl.col(EVENT_COLUMN).hash(seed=seed) % folds).alias("_fold"),
            ]
        )
        .group_by([*keys, "_fold"])
        .agg([pl.col("_v").sum().alias("_fsum"), pl.len().alias("_fcount")])
    )
    return frame.collect(engine="streaming")


def _encode_one(
    frame: pl.DataFrame,
    stats: pl.DataFrame,
    keys: list[str],
    name: str,
    prior: pl.Expr,
    smoothing: float,
) -> pl.DataFrame:
    """Blend a group's observed mean with a per-row prior by observation count.

        enc = (group_sum + prior * m) / (group_count + m)

    An unmatched group has count 0 and collapses to `prior` exactly, so smoothing and
    the missing-value fallback are the same mechanism rather than two policies that can
    disagree.
    """
    return (
        frame.join(stats, on=keys, how="left")
        .with_columns(
            [
                (
                    (pl.col("_sum").fill_null(0.0) + prior * smoothing)
                    / (pl.col("_count").fill_null(0).cast(pl.Float64) + smoothing)
                ).alias(name),
                pl.col("_count").fill_null(0).cast(pl.Float64).log1p().alias(f"{name}__n"),
            ]
        )
        .drop(["_sum", "_count"])
    )


def _group_stats(frame: pl.DataFrame, keys: list[str], value_column: str) -> pl.DataFrame:
    return frame.group_by(keys).agg(
        [
            pl.col(value_column).sum().cast(pl.Float64).alias("_sum"),
            pl.len().alias("_count"),
        ]
    )


def add_hierarchical_encoding(
    train: pl.DataFrame,
    test: pl.DataFrame,
    specs: list[tuple[list[str], str]],
    folds: int,
    smoothing: float,
    seed: int,
    stats_source: Callable[[list[str], str], pl.DataFrame] | None = None,
) -> tuple[pl.DataFrame, pl.DataFrame, list[str], dict[str, Any]]:
    """Historical aggregates with a horizon-aware backoff prior.

    When `stats_source` is supplied it returns per-(keys, fold) sums computed over every
    training row on disk rather than over the sampled frame, and the out-of-fold value for
    a train row becomes total-minus-own-fold. Without it the aggregates come from the
    sampled frame alone.

    The prior is per-row (route pace x remaining stops), not a global constant — see
    train_advanced.py's module docstring for why a global-constant prior actively hurt
    unseen-route rows in an earlier version. Every encoding ships a companion `__n` column
    holding log1p(group count) so the model can learn when to distrust a sparse group.

    Folds are assigned by arrival event, not by row: one arrival produces ~33 snapshots
    sharing a single label, so a row-wise split would let a row's own arrival feed the
    aggregate it is scored against.
    """
    train = train.with_row_index("_row_id")
    if EVENT_COLUMN in train.columns:
        train = train.with_columns((pl.col(EVENT_COLUMN).hash(seed=seed) % folds).alias("_fold"))
    else:
        rng = np.random.default_rng(seed)
        train = train.with_columns(pl.Series("_fold", rng.integers(0, folds, size=train.height)))

    global_pace = float(train["_te_pace"].median() or 120.0)
    print(f"[encode] global prior pace = {global_pace:.2f} s/stop")

    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    created: list[str] = []
    # Per-spec (keys + {_sum, _count}) totals tables — the same aggregate a serve-time
    # lookup needs to reproduce this exact encoding for a live request (test rows and an
    # unseen live request are both scored against the plain total, never an OOF split).
    serving_tables: dict[str, pl.DataFrame] = {}

    for index, (keys, value) in enumerate(specs):
        if any(key not in train.columns or key not in test.columns for key in keys):
            print(f"[encode] skipping {keys}: column missing")
            continue
        name = encoding_name(keys, value)
        value_column = "_te_target" if value == "target" else "_te_pace"

        # First spec establishes the backoff prior; everything after it leans on that.
        if index == 0:
            prior = pl.lit(global_pace)
        elif value == "pace":
            prior = pl.col(PRIOR_PACE_COLUMN)
        else:
            prior = pl.col(PRIOR_PACE_COLUMN) * remaining

        if stats_source is not None:
            per_fold = stats_source(keys, value)
            totals = per_fold.group_by(keys).agg(
                [pl.col("_fsum").sum().alias("_sum"), pl.col("_fcount").sum().alias("_count")]
            )
            # Test rows never appear in the aggregate — its source dates end before the
            # test week — so they take the full total.
            test = _encode_one(test, totals, keys, name, prior, smoothing)
            # A train row takes the total minus its own fold, which removes its own
            # arrival event along with every other event that hashed into that fold.
            oof = (
                per_fold.join(totals, on=keys, how="left")
                .with_columns(
                    [
                        (pl.col("_sum") - pl.col("_fsum")).alias("_sum"),
                        (pl.col("_count") - pl.col("_fcount")).alias("_count"),
                    ]
                )
                .select([*keys, "_fold", "_sum", "_count"])
            )
            train = _encode_one(train, oof, [*keys, "_fold"], name, prior, smoothing)
        else:
            totals = _group_stats(train, keys, value_column)
            test = _encode_one(test, totals, keys, name, prior, smoothing)
            parts: list[pl.DataFrame] = []
            for fold in range(folds):
                holdout = train.filter(pl.col("_fold") == fold)
                if holdout.is_empty():
                    continue
                rest = train.filter(pl.col("_fold") != fold)
                stats = (
                    _group_stats(rest, keys, value_column)
                    if not rest.is_empty()
                    else holdout.head(0).select(keys).with_columns(
                        [pl.lit(0.0).alias("_sum"), pl.lit(0).alias("_count")]
                    )
                )
                parts.append(_encode_one(holdout, stats, keys, name, prior, smoothing))
            train = pl.concat(parts, how="vertical_relaxed").sort("_row_id")

        serving_tables[name] = totals
        created.extend([name, f"{name}__n"])
        if index == 0:
            train = train.with_columns(pl.col(name).alias(PRIOR_PACE_COLUMN))
            test = test.with_columns(pl.col(name).alias(PRIOR_PACE_COLUMN))
        print(f"[encode] {name} done", flush=True)

    # A pace encoding is only actionable in seconds, so project it through the leg length.
    projected: list[str] = []
    for name in [item for item in created if item.startswith("te_pace__") and not item.endswith("__n")]:
        projected_name = f"{name}__x_stops"
        expression = (pl.col(name) * remaining).alias(projected_name)
        train = train.with_columns(expression)
        test = test.with_columns(expression)
        projected.append(projected_name)

    train = train.drop(["_row_id", "_fold"])
    serving_stats = {
        "global_pace": global_pace,
        "prior_pace_column": PRIOR_PACE_COLUMN,
        # Order matters at serve time: later specs' prior is derived from the first
        # spec's per-row encoded value, so a lookup must apply these in this order.
        "spec_order": [encoding_name(keys, value) for keys, value in specs],
        "tables": serving_tables,
    }
    return train, test, created + projected, serving_stats


def build_feature_frames(
    train: pl.DataFrame,
    test: pl.DataFrame,
    args: Any,
) -> tuple[pl.DataFrame, pl.DataFrame, list[str], dict[str, Any]]:
    """Orchestrates route_profile -> derived features -> hierarchical encoding into one
    feature list, exactly as train_advanced.py's research pipeline does.

    `args` needs: target_column, serving_parity, route_agnostic (optional), api_oracle,
    no_target_encoding, full_data_encoding, data_dir, encoding_dates, te_folds,
    te_smoothing, seed, and (for the caller's own numeric base + spec choice)
    numeric_features, encoding_specs, route_agnostic_specs.
    """
    policy: dict[str, Any] = {}
    target_column = args.target_column

    profile = route_profile(train)
    train = add_derived_features(train, profile)
    test = add_derived_features(test, profile)

    numeric = [column for column in args.numeric_features if column in train.columns]
    if not args.serving_parity:
        numeric += [column for column in NON_SERVED_FEATURES if column in train.columns]
    derived = [column for column in DERIVED_FEATURES if column in train.columns]
    specs = args.encoding_specs
    if getattr(args, "route_agnostic", False):
        derived = [column for column in derived if column not in ROUTE_PROFILE_FEATURES]
        specs = args.route_agnostic_specs
    features = numeric + derived

    if args.api_oracle:
        features.append(API_COLUMN)
        policy["api_oracle"] = True

    if not args.no_target_encoding:
        remaining = pl.col("remaining_stop_count").cast(pl.Float64)
        safe_remaining = pl.when(remaining > 0).then(remaining).otherwise(pl.lit(1.0))
        train = train.with_columns(
            [
                pl.col(target_column).cast(pl.Float64).alias("_te_target"),
                (pl.col(target_column).cast(pl.Float64) / safe_remaining).alias("_te_pace"),
            ]
        )
        stats_source = None
        if args.full_data_encoding:
            cache: dict[tuple[str, str], pl.DataFrame] = {}

            def stats_source(keys: list[str], value: str) -> pl.DataFrame:
                cache_key = ("+".join(keys), value)
                if cache_key not in cache:
                    started = time.perf_counter()
                    cache[cache_key] = full_data_stats(
                        args.data_dir,
                        args.encoding_dates,
                        target_column,
                        keys,
                        value,
                        args.te_folds,
                        args.seed,
                    )
                    print(
                        f"[encode] full-data scan {cache_key} -> "
                        f"{cache[cache_key].height:,} groups in "
                        f"{time.perf_counter() - started:.0f}s",
                        flush=True,
                    )
                return cache[cache_key]

        train, test, encoded, serving_stats = add_hierarchical_encoding(
            train,
            test,
            specs,
            args.te_folds,
            args.te_smoothing,
            args.seed,
            stats_source,
        )
        features += encoded
        policy["target_encoding"] = {
            "specs": [f"{value}:{'+'.join(keys)}" for keys, value in specs],
            "folds": args.te_folds,
            "smoothing": args.te_smoothing,
            "fold_grouping": EVENT_COLUMN if EVENT_COLUMN in train.columns else "row",
            "prior": "hierarchical (route pace x remaining stops), not a global constant",
        }
        # Everything a serve-time lookup needs to reproduce these encodings for a live
        # request: the route profile (for the route-keyed derived features) and, per
        # encoding spec, the same totals table + global prior test rows were scored
        # against (see add_hierarchical_encoding's serving_tables).
        policy["route_profile"] = profile
        policy["encoding_serving_stats"] = serving_stats

    leaked = [column for column in features if column in LEAKAGE_COLUMNS and column != API_COLUMN]
    if leaked:
        raise ValueError(f"Leakage columns entered the feature set: {leaked}")

    dropped = [column for column in features if train[column].null_count() == train.height]
    features = [column for column in features if column not in dropped]
    policy["dropped_all_null_features"] = dropped
    policy["feature_count"] = len(features)
    return train, test, features, policy


def compute_serving_features(
    frame: pl.DataFrame,
    profile: pl.DataFrame,
    tables: dict[str, pl.DataFrame],
    global_pace: float,
    smoothing: float,
    specs: list[tuple[list[str], str]] = SERVABLE_ENCODING_SPECS,
) -> pl.DataFrame:
    """Reproduce, for a live /predict request, the exact same derived + te_* columns
    add_hierarchical_encoding computes for test rows (see the ``stats_source is not
    None`` branch's ``test = _encode_one(test, totals, ...)`` line) — a live request
    was never in training, so like a test row it always takes the full persisted
    total, never an out-of-fold split.

    ``tables`` maps each spec's ``encoding_name(keys, value)`` to its persisted
    (keys + {_sum, _count}) totals table (``persist_encoding_artifacts`` writes these
    to ``encoding_tables/*.parquet``; ``profile`` is the persisted route_profile).

    A live request frame is built fresh from JSON, so its key-column dtypes won't
    naturally match what training wrote to parquet — align them first, or the joins
    below raise (or silently produce all-null misses).
    """
    reference_frames = [profile, *tables.values()]
    for key in {key for keys, _ in specs for key in keys} | {"route_id"}:
        for reference in reference_frames:
            if key in frame.columns and key in reference.columns:
                frame = frame.with_columns(pl.col(key).cast(reference.schema[key]))
                break

    frame = add_derived_features(frame, profile)
    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    created: list[str] = []

    for index, (keys, value) in enumerate(specs):
        name = encoding_name(keys, value)
        if index == 0:
            prior = pl.lit(global_pace)
        elif value == "pace":
            prior = pl.col(PRIOR_PACE_COLUMN)
        else:
            prior = pl.col(PRIOR_PACE_COLUMN) * remaining
        frame = _encode_one(frame, tables[name], keys, name, prior, smoothing)
        created.extend([name, f"{name}__n"])
        if index == 0:
            frame = frame.with_columns(pl.col(name).alias(PRIOR_PACE_COLUMN))

    for name in [item for item in created if item.startswith("te_pace__") and not item.endswith("__n")]:
        frame = frame.with_columns((pl.col(name) * remaining).alias(f"{name}__x_stops"))

    return frame.drop(PRIOR_PACE_COLUMN)


def persist_encoding_artifacts(output_dir: Path, policy: dict[str, Any]) -> dict[str, Any]:
    """Write route_profile/encoding tables to output_dir/encoding_tables/*.parquet and
    return a JSON-safe copy of `policy` with those pl.DataFrame values replaced by their
    file paths.

    build_feature_frames' policy dict embeds live DataFrames under "route_profile" and
    "encoding_serving_stats"["tables"] — write_json/json.dump cannot serialize those, and
    a serve-time lookup needs these tables on disk anyway to reproduce the encoding for a
    live request. Call this before any manifest write whenever target encoding is enabled.
    """
    if "route_profile" not in policy and "encoding_serving_stats" not in policy:
        return policy

    tables_dir = output_dir / "encoding_tables"
    tables_dir.mkdir(parents=True, exist_ok=True)
    safe = dict(policy)

    if "route_profile" in safe:
        path = tables_dir / "route_profile.parquet"
        safe["route_profile"].write_parquet(path)
        safe["route_profile"] = str(path)

    if "encoding_serving_stats" in safe:
        stats = dict(safe["encoding_serving_stats"])
        table_paths: dict[str, str] = {}
        for name, table in stats.get("tables", {}).items():
            path = tables_dir / f"{name}.parquet"
            table.write_parquet(path)
            table_paths[name] = str(path)
        stats["tables"] = table_paths
        safe["encoding_serving_stats"] = stats

    return safe
