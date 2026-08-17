"""Tests for feature_engineering.py's JSON-safety helper.

Run: uv run pytest test_feature_engineering.py
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import polars as pl
import pytest

from feature_engineering import (
    SERVABLE_ENCODING_SPECS,
    _group_stats,
    compute_serving_features,
    encoding_name,
    persist_encoding_artifacts,
    route_profile,
)


def test_persist_encoding_artifacts_replaces_dataframes_with_paths(tmp_path: Path):
    route_profile = pl.DataFrame({"route_id": ["1", "2"], "route_total_stops": [10, 20]})
    table = pl.DataFrame({"route_id": ["1", "2"], "_sum": [100.0, 200.0], "_count": [5, 10]})
    policy = {
        "route_profile": route_profile,
        "encoding_serving_stats": {
            "global_pace": 88.6,
            "prior_pace_column": "_prior_pace",
            "spec_order": ["te_pace__route_id"],
            "tables": {"te_pace__route_id": table},
        },
        "feature_count": 12,
    }

    safe = persist_encoding_artifacts(tmp_path, policy)

    # Every value must now be JSON-serializable: no leftover pl.DataFrame anywhere.
    json.dumps(safe)

    assert isinstance(safe["route_profile"], str)
    assert pl.read_parquet(safe["route_profile"]).equals(route_profile)

    table_path = safe["encoding_serving_stats"]["tables"]["te_pace__route_id"]
    assert isinstance(table_path, str)
    assert pl.read_parquet(table_path).equals(table)
    assert safe["encoding_serving_stats"]["global_pace"] == 88.6
    assert safe["feature_count"] == 12


def test_persist_encoding_artifacts_is_a_noop_without_encoding_keys(tmp_path: Path):
    policy = {"feature_count": 5, "dropped_all_null_features": []}
    assert persist_encoding_artifacts(tmp_path, policy) == policy
    assert not (tmp_path / "encoding_tables").exists()


def _synthetic_train() -> pl.DataFrame:
    # Two routes, each seen at two hours, so the first two SERVABLE_ENCODING_SPECS
    # (route_id alone, route_id+hour_of_day) have real (non-trivial) group stats.
    return pl.DataFrame(
        {
            "route_id": ["143", "143", "143", "272", "272", "272"],
            "hour_of_day": [8, 8, 9, 8, 9, 9],
            "current_section_order": [1, 2, 3, 1, 2, 3],
            "target_seq": [5, 5, 5, 4, 4, 4],
            "remaining_stop_count": [4, 3, 2, 3, 2, 1],
            "congestion": [1.0, 2.0, 1.5, 0.5, 1.0, 2.0],
            "current_full_section_distance": [500.0, 500.0, 500.0, 400.0, 400.0, 400.0],
            "minute_of_day": [480, 490, 540, 480, 545, 550],
            "day_of_week": [1, 1, 2, 1, 2, 2],
            "target_seconds": [200.0, 240.0, 180.0, 150.0, 130.0, 160.0],
        }
    )


def test_compute_serving_features_reproduces_train_time_blend(tmp_path: Path):
    train = _synthetic_train()
    target_column = "target_seconds"
    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    train = train.with_columns(
        [
            pl.col(target_column).alias("_te_target"),
            (pl.col(target_column) / remaining).alias("_te_pace"),
        ]
    )
    global_pace = float(train["_te_pace"].median())
    smoothing = 50.0

    tables = {}
    for keys, value in SERVABLE_ENCODING_SPECS:
        value_column = "_te_target" if value == "target" else "_te_pace"
        tables[encoding_name(keys, value)] = _group_stats(train, keys, value_column)
    profile = route_profile(train)

    # Round-trip through parquet exactly as production does (persist -> pl.read_parquet),
    # and store hour_of_day/current_section_order/target_seq as Int64 — the same dtype
    # `_group_stats` produced them in above — while the "live request" below deliberately
    # sends them as Float64 (as a JSON-built serve request would), to prove the dtype
    # alignment in compute_serving_features is what makes the join succeed, not luck.
    policy = {
        "route_profile": profile,
        "encoding_serving_stats": {"tables": tables},
    }
    safe = persist_encoding_artifacts(tmp_path, policy)
    loaded_profile = pl.read_parquet(safe["route_profile"])
    loaded_tables = {
        name: pl.read_parquet(path)
        for name, path in safe["encoding_serving_stats"]["tables"].items()
    }

    live_request = pl.DataFrame(
        {
            "route_id": ["143"],
            "hour_of_day": [8.0],
            "current_section_order": [1.0],
            "target_seq": [5.0],
            "remaining_stop_count": [4.0],
            "congestion": [1.0],
            "current_full_section_distance": [500.0],
            "minute_of_day": [480.0],
            "day_of_week": [1.0],
        }
    )

    result = compute_serving_features(
        live_request, loaded_profile, loaded_tables, global_pace, smoothing
    )

    # Manually reproduce the expected blend for the first two specs (route_id alone,
    # then route_id+hour_of_day chained off the first spec's own output as its prior) —
    # computed independently here, not by re-calling the code under test.
    route_rows = train.filter(pl.col("route_id") == "143")
    route_sum, route_count = float(route_rows["_te_pace"].sum()), route_rows.height
    expected_route_pace = (route_sum + global_pace * smoothing) / (route_count + smoothing)
    assert result["te_pace__route_id"][0] == pytest.approx(expected_route_pace)
    assert result["te_pace__route_id__n"][0] == pytest.approx(math.log1p(route_count))

    group_rows = train.filter((pl.col("route_id") == "143") & (pl.col("hour_of_day") == 8))
    group_sum, group_count = float(group_rows["_te_pace"].sum()), group_rows.height
    expected_group_pace = (group_sum + expected_route_pace * smoothing) / (group_count + smoothing)
    assert result["te_pace__route_id_hour_of_day"][0] == pytest.approx(expected_group_pace)

    # __x_stops projects every te_pace__* (non __n) column through remaining_stop_count.
    assert result["te_pace__route_id__x_stops"][0] == pytest.approx(expected_route_pace * 4.0)

    # Internal bookkeeping column must not leak into the servable feature frame.
    assert "_prior_pace" not in result.columns

    # Route-profile-derived feature, joined from the persisted (parquet round-tripped)
    # profile — proves that side of the load-from-disk path works too.
    assert result["route_total_stops"][0] == pytest.approx(5.0)


def test_compute_serving_features_unseen_group_collapses_to_prior(tmp_path: Path):
    """A live request for a route/hour combination never seen in training must fall
    back to the prior exactly (count=0), per _encode_one's documented behavior —
    not error or silently return null."""
    train = _synthetic_train()
    target_column = "target_seconds"
    remaining = pl.col("remaining_stop_count").cast(pl.Float64)
    train = train.with_columns(
        [
            pl.col(target_column).alias("_te_target"),
            (pl.col(target_column) / remaining).alias("_te_pace"),
        ]
    )
    global_pace = float(train["_te_pace"].median())
    smoothing = 50.0
    tables = {}
    for keys, value in SERVABLE_ENCODING_SPECS:
        value_column = "_te_target" if value == "target" else "_te_pace"
        tables[encoding_name(keys, value)] = _group_stats(train, keys, value_column)
    profile = route_profile(train)

    live_request = pl.DataFrame(
        {
            "route_id": ["999999"],  # never seen in training
            "hour_of_day": [8.0],
            "current_section_order": [1.0],
            "target_seq": [5.0],
            "remaining_stop_count": [4.0],
            "congestion": [1.0],
            "current_full_section_distance": [500.0],
            "minute_of_day": [480.0],
            "day_of_week": [1.0],
        }
    )

    result = compute_serving_features(live_request, profile, tables, global_pace, smoothing)

    assert result["te_pace__route_id"][0] == pytest.approx(global_pace)
    assert result["te_pace__route_id__n"][0] == pytest.approx(0.0)
    # route_profile has no row for this route either -> derived ratios are null, not an error.
    assert result["route_total_stops"][0] is None
