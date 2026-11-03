"""Real-time features from a route's recent position history.

The same function serves training (``build_dataset.py`` hands it a whole service
day of positions per route) and serving (``serve/app.py`` hands it the last
``HISTORY_WINDOW`` of observations the Java app accumulated for the route). Every
lookup is causal (only observations at or before the query snapshot) and bounded
by ``HISTORY_WINDOW``, so both paths see the same information and produce the
same values — ``test_realtime_features.py`` checks that parity.

Features (all seconds, NaN when the history does not support them):

- ``rt_elapsed_in_section``  time since this vehicle entered its current section
- ``rt_own_recent_pace``     seconds per section over the vehicle's last <=5 sections
- ``rt_headway``             how long after the bus ahead this vehicle entered the section
- ``rt_leader_dur``          the bus ahead's observed time from this section to the target
- ``rt_leader_elapsed_open`` if the bus ahead has not reached the target yet: time since it
                             entered this section (a lower bound on its duration)
- ``rt_leader2_dur``         same as rt_leader_dur for the bus two ahead
- ``rt_leader_age``          how long ago the bus ahead reached the target

This module depends on polars only: serving imports it too.
"""

from __future__ import annotations

from datetime import timedelta

import polars as pl

HISTORY_WINDOW = timedelta(minutes=90)
MAX_HEADWAY = timedelta(minutes=60)
OWN_PACE_SECTIONS = 5

REALTIME_FEATURES = [
    "rt_elapsed_in_section",
    "rt_own_recent_pace",
    "rt_headway",
    "rt_leader_dur",
    "rt_leader_elapsed_open",
    "rt_leader2_dur",
    "rt_leader_age",
]

HISTORY_COLUMNS = ["route_id", "vehicle_identity", "snapshot_at", "current_section_order"]
QUERY_COLUMNS = [*HISTORY_COLUMNS, "target_seq"]

TRIP_INDEX_COLUMN = "_trip_index"
# A new trip starts when section_order falls back by more than this many sections
# (lap reset / turnaround), or when the vehicle goes silent for longer than the gap.
TRIP_BREAK_SECTION_DROP = 2
TRIP_BREAK_GAP = timedelta(minutes=20)

_KEYS = ["route_id", "vehicle_identity", TRIP_INDEX_COLUMN]


def assign_trip_index(position: pl.LazyFrame) -> pl.LazyFrame:
    """Number each vehicle's consecutive snapshots into trips (0, 1, 2, ...)."""
    keys = ["route_id", "vehicle_identity"]
    previous_order = pl.col("current_section_order").shift(1).over(keys)
    previous_time = pl.col("snapshot_at").shift(1).over(keys)
    is_break = (
        (pl.col("current_section_order") < previous_order - TRIP_BREAK_SECTION_DROP)
        | ((pl.col("snapshot_at") - previous_time) > TRIP_BREAK_GAP)
    ).fill_null(True)
    return position.sort([*keys, "snapshot_at"]).with_columns(
        (is_break.cast(pl.Int32).cum_sum().over(keys) - 1).alias(TRIP_INDEX_COLUMN)
    )


def _normalize(frame: pl.DataFrame, columns: list[str]) -> pl.DataFrame:
    return frame.select(columns).with_columns(
        pl.col("route_id").cast(pl.Utf8),
        pl.col("vehicle_identity").cast(pl.Utf8),
        pl.col("snapshot_at").cast(pl.Datetime("us")),
        pl.col("current_section_order").cast(pl.Int32),
    )


def section_passes(history: pl.DataFrame) -> pl.DataFrame:
    """When each vehicle trip entered each section.

    A snapshot that advances the running max section by n sections yields n entry
    events, spaced evenly between the previous and current snapshot. The first
    observed section of a trip carries ``is_trip_start``: its entry time is unknown
    (the vehicle was already there when first seen), so consumers skip it.
    """
    pos = (
        history.unique(["route_id", "vehicle_identity", "snapshot_at"], keep="first")
        .lazy()
        .pipe(assign_trip_index)
        .with_columns(pl.col("current_section_order").cum_max().over(_KEYS).alias("_cm"))
        .with_columns(
            pl.col("_cm").shift(1).over(_KEYS).alias("_prev_cm"),
            pl.col("snapshot_at").shift(1).over(_KEYS).alias("_prev_t"),
        )
        .collect()
    )
    events = pos.filter(pl.col("_prev_cm").is_null() | (pl.col("_cm") > pl.col("_prev_cm")))
    is_start = pl.col("_prev_cm").is_null()
    events = events.with_columns(
        pl.when(is_start).then(pl.col("_cm")).otherwise(pl.col("_prev_cm") + 1).alias("_k0"),
    ).with_columns(pl.int_ranges("_k0", pl.col("_cm") + 1).alias("k")).explode("k")
    span_ms = (pl.col("snapshot_at") - pl.col("_prev_t")).dt.total_milliseconds()
    fraction = (pl.col("k") - pl.col("_prev_cm") - 0.5) / (pl.col("_cm") - pl.col("_prev_cm"))
    entered_at = (
        pl.when(is_start)
        .then(pl.col("snapshot_at"))
        .otherwise(pl.col("_prev_t") + pl.duration(milliseconds=(span_ms * fraction).cast(pl.Int64)))
    )
    return events.select(
        *_KEYS,
        pl.col("k").cast(pl.Int32),
        entered_at.alias("entered_at"),
        is_start.alias("is_trip_start"),
    )


def compute_realtime_features(history: pl.DataFrame, queries: pl.DataFrame) -> pl.DataFrame:
    """Real-time features for ``queries`` from ``history``; one row per query, in order.

    ``history`` needs HISTORY_COLUMNS; ``queries`` needs QUERY_COLUMNS. Rows of
    ``queries`` must also be present in ``history`` (the current observation is part
    of the vehicle's trajectory) — otherwise their own-trajectory features are NaN.
    """
    queries = queries.with_row_index("_q")
    q = _normalize(queries, QUERY_COLUMNS).with_columns(
        queries["_q"], pl.col("target_seq").cast(pl.Int32)
    )
    if history.is_empty():
        return _empty(queries)
    passes = section_passes(_normalize(history, HISTORY_COLUMNS))
    usable = passes.filter(~pl.col("is_trip_start"))
    t = pl.col("snapshot_at")
    window_start = t - HISTORY_WINDOW

    # own trip: which trip the query snapshot belongs to, and when it entered section c
    trips = (
        _normalize(history, HISTORY_COLUMNS)
        .unique(["route_id", "vehicle_identity", "snapshot_at"], keep="first")
        .lazy()
        .pipe(assign_trip_index)
        .collect()
        .select("route_id", "vehicle_identity", "snapshot_at", TRIP_INDEX_COLUMN)
    )
    q = q.join(trips, on=["route_id", "vehicle_identity", "snapshot_at"], how="left")
    own = usable.select(
        *_KEYS, pl.col("k").alias("current_section_order"), pl.col("entered_at").alias("own_tc")
    )
    q = q.join(own, on=[*_KEYS, "current_section_order"], how="left")

    # own recent pace: earliest usable entry within the last OWN_PACE_SECTIONS sections.
    # Joined on the exact candidate sections (<= OWN_PACE_SECTIONS rows per query), not
    # on the whole trip — a trip-wide join multiplies every query row by the trip's
    # section count and ran a big service day out of memory.
    back = usable.select(*_KEYS, pl.col("k").alias("_bk"), pl.col("entered_at").alias("_bt"))
    candidates = (
        q.select("_q", *_KEYS, "current_section_order", "snapshot_at")
        .with_columns(
            pl.int_ranges(
                pl.col("current_section_order") - OWN_PACE_SECTIONS, pl.col("current_section_order")
            )
            .cast(pl.List(pl.Int32))
            .alias("_bk")
        )
        .explode("_bk")
        .join(back, on=[*_KEYS, "_bk"], how="inner")
        .filter(pl.col("_bt") >= pl.col("snapshot_at") - HISTORY_WINDOW)
        .sort("_bk")
        .group_by("_q", maintain_order=True)
        .agg(pl.col("_bk").first().alias("back_k"), pl.col("_bt").first().alias("own_tb"))
    )
    q = q.join(candidates, on="_q", how="left")

    # leader: the latest other entry into section c before this vehicle's entry
    lead = usable.select(
        "route_id",
        pl.col("k").alias("current_section_order"),
        pl.col("vehicle_identity").alias("l_veh"),
        pl.col(TRIP_INDEX_COLUMN).alias("l_trip"),
        pl.col("entered_at").alias("l_tc"),
    ).sort("l_tc")
    q = q.sort("own_tc", nulls_last=True)
    with_tc = q.filter(pl.col("own_tc").is_not_null()).join_asof(
        lead,
        left_on="own_tc",
        right_on="l_tc",
        by=["route_id", "current_section_order"],
        strategy="backward",
        allow_exact_matches=False,
        check_sortedness=False,
    )
    q = pl.concat([with_tc, q.filter(pl.col("own_tc").is_null())], how="diagonal")
    at_target = usable.select(
        "route_id",
        pl.col("vehicle_identity").alias("l_veh"),
        pl.col(TRIP_INDEX_COLUMN).alias("l_trip"),
        pl.col("k").alias("target_seq"),
        pl.col("entered_at").alias("l_ts"),
    )
    q = q.join(at_target, on=["route_id", "l_veh", "l_trip", "target_seq"], how="left")

    lead2 = lead.rename({"l_veh": "l2_veh", "l_trip": "l2_trip", "l_tc": "l2_tc"})
    q = q.sort("l_tc", nulls_last=True)
    with_l = q.filter(pl.col("l_tc").is_not_null()).join_asof(
        lead2,
        left_on="l_tc",
        right_on="l2_tc",
        by=["route_id", "current_section_order"],
        strategy="backward",
        allow_exact_matches=False,
        check_sortedness=False,
    )
    q = pl.concat([with_l, q.filter(pl.col("l_tc").is_null())], how="diagonal")
    at_target2 = at_target.rename({"l_veh": "l2_veh", "l_trip": "l2_trip", "l_ts": "l2_ts"})
    q = q.join(at_target2, on=["route_id", "l2_veh", "l2_trip", "target_seq"], how="left")

    def seconds(expr: pl.Expr) -> pl.Expr:
        return expr.dt.total_milliseconds().cast(pl.Float64) / 1000.0

    headway = seconds(pl.col("own_tc") - pl.col("l_tc"))
    leader_ok = (pl.col("l_tc") >= window_start) & (headway <= MAX_HEADWAY.total_seconds())
    leader_done = leader_ok & pl.col("l_ts").is_not_null() & (pl.col("l_ts") <= t)
    leader2_ok = (
        leader_ok
        & (pl.col("l2_tc") >= window_start)
        & pl.col("l2_ts").is_not_null()
        & (pl.col("l2_ts") <= t)
    )
    own_ok = pl.col("own_tc").is_not_null() & (pl.col("own_tc") >= window_start)

    features = q.select(
        "_q",
        pl.when(own_ok).then(seconds(t - pl.col("own_tc"))).alias("rt_elapsed_in_section"),
        pl.when(own_ok & pl.col("own_tb").is_not_null())
        .then(
            seconds(pl.col("own_tc") - pl.col("own_tb"))
            / (pl.col("current_section_order") - pl.col("back_k")).cast(pl.Float64)
        )
        .alias("rt_own_recent_pace"),
        pl.when(own_ok & leader_ok).then(headway).alias("rt_headway"),
        pl.when(own_ok & leader_done)
        .then(seconds(pl.col("l_ts") - pl.col("l_tc")))
        .alias("rt_leader_dur"),
        pl.when(own_ok & leader_ok & ~leader_done)
        .then(seconds(t - pl.col("l_tc")))
        .alias("rt_leader_elapsed_open"),
        pl.when(own_ok & leader2_ok)
        .then(seconds(pl.col("l2_ts") - pl.col("l2_tc")))
        .alias("rt_leader2_dur"),
        pl.when(own_ok & leader_done).then(seconds(t - pl.col("l_ts"))).alias("rt_leader_age"),
    )
    out = queries.select("_q").join(features, on="_q", how="left").sort("_q")
    return out.select([pl.col(c).cast(pl.Float64).fill_null(float("nan")) for c in REALTIME_FEATURES])


def _empty(queries: pl.DataFrame) -> pl.DataFrame:
    return pl.DataFrame(
        {c: pl.Series([float("nan")] * queries.height, dtype=pl.Float64) for c in REALTIME_FEATURES}
    )


def add_realtime_features(rows: pl.DataFrame, history: pl.DataFrame) -> pl.DataFrame:
    """Append REALTIME_FEATURES columns to ``rows`` (which carry QUERY_COLUMNS)."""
    features = compute_realtime_features(history, rows)
    return pl.concat([rows, features], how="horizontal")
