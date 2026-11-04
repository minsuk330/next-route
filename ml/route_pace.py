"""Route-wide recent pace: how the whole route is moving right now versus usual.

Complements realtime_features.py (which looks only at the bus ahead). Every bus
on the route that finished a section in the last ROUTE_PACE_WINDOW contributes a
ratio of observed to reference traversal time; the mean of those ratios scales
the reference time of the path still ahead of the query bus.

Reference = per (route_id, k) median seconds to traverse section k (enter k ->
enter k+1), built from archived positions by section_reference.py and shipped with
the model as ``encoding_tables/section_reference.parquet`` so serving uses the
same table training did.

Features (NaN when the history or the reference does not support them):

- ``rt_route_ratio_30m``      mean observed/reference traversal ratio on this route
                              over the last 30 minutes (1.0 = usual, 1.5 = 50% slower)
- ``rt_path_ref_seconds``     reference time from the current section to the target
- ``rt_path_expected_seconds`` the same path scaled by rt_route_ratio_30m

Like realtime_features, every lookup is causal and bounded, so a live request
scored from the app's rolling history reproduces the training-time value.
"""

from __future__ import annotations

from datetime import timedelta

import polars as pl

from realtime_features import HISTORY_COLUMNS, TRIP_INDEX_COLUMN, section_passes

ROUTE_PACE_WINDOW = timedelta(minutes=30)
MAX_TRAVERSAL_SECONDS = 1800
RATIO_CLIP = (0.2, 5.0)

ROUTE_PACE_FEATURES = [
    "rt_route_ratio_30m",
    "rt_path_ref_seconds",
    "rt_path_expected_seconds",
]
REFERENCE_COLUMNS = ["route_id", "k", "median_seconds", "n"]

_KEYS = ["route_id", "vehicle_identity", TRIP_INDEX_COLUMN]


def section_events(history: pl.DataFrame) -> pl.DataFrame:
    """Completed traversals: (route_id, k, entered_at, left_at, seconds)."""
    passes = section_passes(history)
    return (
        passes.sort([*_KEYS, "k"])
        .with_columns(
            pl.col("entered_at").shift(-1).over(_KEYS).alias("left_at"),
            pl.col("k").shift(-1).over(_KEYS).alias("_k_next"),
        )
        .filter((pl.col("_k_next") == pl.col("k") + 1) & ~pl.col("is_trip_start"))
        .with_columns(
            (pl.col("left_at") - pl.col("entered_at")).dt.total_milliseconds().cast(pl.Float64).alias("_ms")
        )
        .with_columns((pl.col("_ms") / 1000.0).alias("seconds"))
        .filter((pl.col("seconds") > 1) & (pl.col("seconds") <= MAX_TRAVERSAL_SECONDS))
        .select("route_id", "k", "entered_at", "left_at", "seconds")
    )


def _normalize_history(history: pl.DataFrame) -> pl.DataFrame:
    return history.select(HISTORY_COLUMNS).with_columns(
        pl.col("route_id").cast(pl.Utf8),
        pl.col("vehicle_identity").cast(pl.Utf8),
        pl.col("snapshot_at").cast(pl.Datetime("us")),
        pl.col("current_section_order").cast(pl.Int32),
    )


def _normalize_reference(reference: pl.DataFrame) -> pl.DataFrame:
    return reference.select(
        pl.col("route_id").cast(pl.Utf8),
        pl.col("k").cast(pl.Int32),
        pl.col("median_seconds").cast(pl.Float64),
    )


def compute_route_pace_features(
    history: pl.DataFrame, queries: pl.DataFrame, reference: pl.DataFrame
) -> pl.DataFrame:
    """One row per query (route_id, snapshot_at, current_section_order, target_seq), in order."""
    queries = queries.with_row_index("_q")
    q = queries.select(
        "_q",
        pl.col("route_id").cast(pl.Utf8),
        pl.col("snapshot_at").cast(pl.Datetime("us")),
        pl.col("current_section_order").cast(pl.Int32),
        pl.col("target_seq").cast(pl.Int32),
    )
    ref = _normalize_reference(reference)

    # path reference: cumulative sum of medians per route so that sum(c..s-1) is two lookups
    cum = ref.sort("route_id", "k").with_columns(
        pl.col("median_seconds").cum_sum().over("route_id").alias("_cum"),
        pl.int_range(pl.len()).over("route_id").alias("_pos"),
    )
    # _cum at k includes section k; path c..s-1 = cum(s-1) - cum(c-1) = cum(s-1) - (cum(c) - median(c))
    at_c = cum.select(
        "route_id",
        pl.col("k").alias("current_section_order"),
        (pl.col("_cum") - pl.col("median_seconds")).alias("_cum_before_c"),
        pl.col("_pos").alias("_pos_c"),
    )
    at_s = cum.select(
        "route_id",
        (pl.col("k") + 1).alias("target_seq"),
        pl.col("_cum").alias("_cum_to_s"),
        pl.col("_pos").alias("_pos_s"),
    )
    q = q.join(at_c, on=["route_id", "current_section_order"], how="left").join(
        at_s, on=["route_id", "target_seq"], how="left"
    )
    # every section between c and s-1 must be in the table (positions are contiguous)
    complete = (
        pl.col("_cum_before_c").is_not_null()
        & pl.col("_cum_to_s").is_not_null()
        & ((pl.col("_pos_s") - pl.col("_pos_c")) == (pl.col("target_seq") - 1 - pl.col("current_section_order")))
        & (pl.col("target_seq") > pl.col("current_section_order"))
    )
    q = q.with_columns(
        pl.when(complete).then(pl.col("_cum_to_s") - pl.col("_cum_before_c")).alias("rt_path_ref_seconds")
    )

    # route-wide recent ratio: rolling mean (30 min) of observed/reference, as of the query time
    if history.is_empty():
        ratio_series = None
    else:
        events = section_events(_normalize_history(history)).join(ref, on=["route_id", "k"], how="inner")
        events = (
            events.with_columns(
                (pl.col("seconds") / pl.col("median_seconds")).clip(*RATIO_CLIP).alias("_ratio")
            )
            .sort("left_at")
            .with_columns(
                pl.col("_ratio")
                .rolling_mean_by("left_at", window_size="30m")
                .over("route_id")
                .alias("rt_route_ratio_30m")
            )
            .select("route_id", "left_at", "rt_route_ratio_30m")
            .sort("left_at")
        )
        ratio_series = events
    if ratio_series is None or ratio_series.is_empty():
        q = q.with_columns(pl.lit(None, dtype=pl.Float64).alias("rt_route_ratio_30m"))
    else:
        q = q.sort("snapshot_at").join_asof(
            ratio_series,
            left_on="snapshot_at",
            right_on="left_at",
            by="route_id",
            strategy="backward",
            tolerance=ROUTE_PACE_WINDOW,
            check_sortedness=False,
        )
    out = (
        q.with_columns(
            (pl.col("rt_path_ref_seconds") * pl.col("rt_route_ratio_30m")).alias("rt_path_expected_seconds")
        )
        .sort("_q")
        .select([pl.col(c).cast(pl.Float64).fill_null(float("nan")) for c in ROUTE_PACE_FEATURES])
    )
    return out


def build_reference(histories: list[pl.DataFrame], min_events: int = 20) -> pl.DataFrame:
    """Per (route_id, k) median traversal seconds over many days of positions."""
    events = pl.concat([section_events(_normalize_history(h)) for h in histories if not h.is_empty()])
    return (
        events.group_by("route_id", "k")
        .agg(pl.col("seconds").median().alias("median_seconds"), pl.len().alias("n"))
        .filter(pl.col("n") >= min_events)
        .sort("route_id", "k")
        .select(REFERENCE_COLUMNS)
    )
