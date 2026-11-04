from __future__ import annotations

import math
from datetime import datetime, timedelta

import polars as pl

from route_pace import ROUTE_PACE_FEATURES, build_reference, compute_route_pace_features, section_events

T0 = datetime(2026, 10, 30, 9, 0, 0)


def _trajectory(vehicle: str, start: datetime, sections: list[int], step: timedelta):
    return [
        {"route_id": "R", "vehicle_identity": vehicle, "snapshot_at": start + i * step, "current_section_order": k}
        for i, k in enumerate(sections)
    ]


def _reference(seconds_per_section: float = 60.0, sections=range(1, 21)) -> pl.DataFrame:
    return pl.DataFrame(
        {"route_id": ["R"] * len(sections), "k": list(sections),
         "median_seconds": [seconds_per_section] * len(sections), "n": [100] * len(sections)}
    )


def _query(at: datetime, section: int, target: int) -> pl.DataFrame:
    return pl.DataFrame({"route_id": ["R"], "snapshot_at": [at], "current_section_order": [section], "target_seq": [target]})


def test_section_events_measure_traversals():
    history = pl.DataFrame(_trajectory("A", T0, [1, 2, 3, 4], timedelta(minutes=2)))
    events = section_events(history)
    # entries at 09:01, 09:03, 09:05 (midpoints) -> sections 2 and 3 took 120 s each
    assert events["k"].to_list() == [2, 3]
    assert events["seconds"].to_list() == [120.0, 120.0]


def test_ratio_and_expected_path_from_slow_route():
    # reference says 60 s per section; buses are taking 120 s -> ratio 2
    rows = _trajectory("A", T0, list(range(1, 11)), timedelta(minutes=2))
    rows += _trajectory("B", T0 + timedelta(minutes=5), list(range(1, 11)), timedelta(minutes=2))
    history = pl.DataFrame(rows)
    feats = compute_route_pace_features(history, _query(T0 + timedelta(minutes=20), 3, 8), _reference())
    row = feats.row(0, named=True)
    assert row["rt_route_ratio_30m"] == 2.0
    assert row["rt_path_ref_seconds"] == 5 * 60.0  # sections 3..7
    assert row["rt_path_expected_seconds"] == 600.0


def test_no_recent_traffic_gives_nan_ratio_but_keeps_reference():
    history = pl.DataFrame(_trajectory("A", T0, [1, 2, 3], timedelta(minutes=1)))
    feats = compute_route_pace_features(history, _query(T0 + timedelta(hours=2), 3, 6), _reference())
    row = feats.row(0, named=True)
    assert math.isnan(row["rt_route_ratio_30m"])
    assert row["rt_path_ref_seconds"] == 180.0
    assert math.isnan(row["rt_path_expected_seconds"])


def test_missing_reference_section_gives_nan_path():
    ref = _reference(sections=[1, 2, 4, 5])  # section 3 missing
    history = pl.DataFrame(_trajectory("A", T0, [1, 2, 3, 4, 5], timedelta(minutes=1)))
    feats = compute_route_pace_features(history, _query(T0 + timedelta(minutes=5), 2, 5), ref)
    assert math.isnan(feats.row(0, named=True)["rt_path_ref_seconds"])
    assert feats.columns == ROUTE_PACE_FEATURES


def test_only_causal_events_count():
    rows = _trajectory("A", T0, list(range(1, 11)), timedelta(minutes=2))
    history = pl.DataFrame(rows)
    at = T0 + timedelta(minutes=6)
    full = compute_route_pace_features(history, _query(at, 2, 6), _reference())
    truncated = compute_route_pace_features(history.filter(pl.col("snapshot_at") <= at), _query(at, 2, 6), _reference())
    assert full.fill_nan(-1.0).equals(truncated.fill_nan(-1.0))


def test_build_reference_medians():
    days = [pl.DataFrame(_trajectory("A", T0 + timedelta(days=d), list(range(1, 8)), timedelta(minutes=1))) for d in range(25)]
    ref = build_reference(days, min_events=20)
    assert set(ref["k"].to_list()) == {2, 3, 4, 5, 6}
    assert ref["median_seconds"].to_list() == [60.0] * 5
