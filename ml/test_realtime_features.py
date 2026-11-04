from __future__ import annotations

import math
from datetime import datetime, timedelta

import polars as pl

from realtime_features import (
    HISTORY_WINDOW,
    REALTIME_FEATURES,
    compute_realtime_features,
    section_passes,
)

T0 = datetime(2026, 10, 30, 9, 0, 0)


def _trajectory(vehicle: str, start: datetime, sections: list[int], step=timedelta(minutes=1)):
    return [
        {
            "route_id": "R",
            "vehicle_identity": vehicle,
            "snapshot_at": start + i * step,
            "current_section_order": k,
        }
        for i, k in enumerate(sections)
    ]


def _history() -> pl.DataFrame:
    # leader L: one section per minute, 1..12 starting 09:00
    # follower F: one section per 2 minutes, 1..6 starting 09:10
    rows = _trajectory("L", T0, list(range(1, 13)))
    rows += _trajectory("F", T0 + timedelta(minutes=10), list(range(1, 7)), timedelta(minutes=2))
    return pl.DataFrame(rows)


def _query(vehicle: str, at: datetime, section: int, target: int) -> pl.DataFrame:
    return pl.DataFrame(
        {
            "route_id": ["R"],
            "vehicle_identity": [vehicle],
            "snapshot_at": [at],
            "current_section_order": [section],
            "target_seq": [target],
        }
    )


def test_section_passes_interpolate_skipped_sections():
    history = pl.DataFrame(_trajectory("V", T0, [1, 1, 4], timedelta(minutes=2)))
    passes = section_passes(history).sort("k")
    assert passes["k"].to_list() == [1, 2, 3, 4]
    assert passes["is_trip_start"].to_list() == [True, False, False, False]
    # sections 2..4 entered evenly inside the 09:02 -> 09:04 gap
    entered = passes.filter(~pl.col("is_trip_start"))["entered_at"].to_list()
    assert entered == [T0 + timedelta(seconds=s) for s in (140, 180, 220)]


def test_leader_features_from_bus_ahead():
    # F at section 3 (entered 09:13:00 by interpolation) asking about stop 6.
    # L entered 3 at 09:01:30 and 6 at 09:04:30 -> leader duration 180s, headway 690s.
    feats = compute_realtime_features(_history(), _query("F", T0 + timedelta(minutes=14), 3, 6))
    row = feats.row(0, named=True)
    assert row["rt_headway"] == 690.0
    assert row["rt_leader_dur"] == 180.0
    assert math.isnan(row["rt_leader_elapsed_open"])
    assert row["rt_elapsed_in_section"] == 60.0
    assert row["rt_own_recent_pace"] == 120.0  # 2 minutes per section
    assert math.isnan(row["rt_leader2_dur"])  # only one bus ahead
    assert row["rt_leader_age"] == (14 * 60) - (4 * 60 + 30)


def test_leader_not_yet_at_target_is_open():
    # L at 09:05 is in section 6; asking about stop 11 which L reaches at 09:09:30.
    feats = compute_realtime_features(_history(), _query("L", T0 + timedelta(minutes=5), 6, 11))
    row = feats.row(0, named=True)
    assert math.isnan(row["rt_leader_dur"])  # nobody ahead of L at all
    assert math.isnan(row["rt_headway"])
    # F's view at 09:12 (section 2): L entered 2 at 09:00:30, reaches 11 at 09:09:30 (<= t) -> done
    feats = compute_realtime_features(_history(), _query("F", T0 + timedelta(minutes=12), 2, 11))
    assert feats.row(0, named=True)["rt_leader_dur"] == 540.0
    # but target 12 is reached at 09:10:30... still <= 09:12 -> done; target beyond trajectory -> open
    feats = compute_realtime_features(_history(), _query("F", T0 + timedelta(minutes=12), 2, 13))
    row = feats.row(0, named=True)
    assert math.isnan(row["rt_leader_dur"])
    assert row["rt_leader_elapsed_open"] == 12 * 60 - 30


def test_no_history_gives_all_nan():
    feats = compute_realtime_features(
        pl.DataFrame(schema={c: t for c, t in zip(
            ["route_id", "vehicle_identity", "snapshot_at", "current_section_order"],
            [pl.Utf8, pl.Utf8, pl.Datetime("us"), pl.Int32])}),
        _query("F", T0, 3, 6),
    )
    assert feats.columns == REALTIME_FEATURES
    assert all(math.isnan(v) for v in feats.row(0))


def test_only_causal_history_is_used():
    full = _history()
    at = T0 + timedelta(minutes=14)
    truncated = full.filter(pl.col("snapshot_at") <= at)
    a = compute_realtime_features(full, _query("F", at, 3, 6))
    b = compute_realtime_features(truncated, _query("F", at, 3, 6))
    assert a.equals(b)


def test_training_and_serving_windows_agree():
    """Training sees the whole day; serving sees the last HISTORY_WINDOW. Same result."""
    rows = []
    for lap in range(6):
        start = T0 + timedelta(minutes=25 * lap)
        rows += _trajectory("A", start, list(range(1, 21)))
        rows += _trajectory("B", start + timedelta(minutes=8), list(range(1, 21)))
        rows += _trajectory("C", start + timedelta(minutes=16), list(range(1, 21)))
    day = pl.DataFrame(rows)
    queries = (
        day.filter(pl.col("snapshot_at") >= T0 + timedelta(minutes=100))
        .with_columns((pl.col("current_section_order") + 5).alias("target_seq"))
        .filter(pl.col("target_seq") <= 20)
    )
    full = compute_realtime_features(day, queries)
    served = []
    for q in queries.iter_rows(named=True):
        window = day.filter(
            (pl.col("snapshot_at") <= q["snapshot_at"])
            & (pl.col("snapshot_at") >= q["snapshot_at"] - HISTORY_WINDOW)
        )
        served.append(compute_realtime_features(window, pl.DataFrame([q])))
    served_frame = pl.concat(served)
    assert full.fill_nan(-1.0).equals(served_frame.fill_nan(-1.0))
    # and the features are actually populated for most rows
    assert full["rt_leader_dur"].is_not_nan().mean() > 0.8
