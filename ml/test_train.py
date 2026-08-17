"""Tests for train.py's routed A/B fallback-model logic.

Run: uv run pytest test_train.py
"""

from __future__ import annotations

import numpy as np
import polars as pl

import pytest

from train import build_router_reports, validate_target_encoding


def _frame(target: list[float], route_id: list[str]) -> pl.DataFrame:
    return pl.DataFrame({"target": target, "route_id": route_id})


def test_router_reports_routes_by_seen_mask():
    test = _frame(target=[100.0, 200.0, 300.0, 400.0], route_id=["143", "143", "999", "999"])
    prediction_a = np.array([100.0, 200.0, 999.0, 999.0])  # perfect on rows 0-1, wrong on 2-3
    prediction_b = np.array([1.0, 1.0, 300.0, 400.0])  # wrong on 0-1, perfect on 2-3
    seen_mask = np.array([True, True, False, False])  # rows 0-1 are "seen" routes -> A

    reports = build_router_reports(test, "target", prediction_a, prediction_b, seen_mask)

    # routed picks A for seen rows, B for unseen rows -> perfect predictions everywhere.
    assert reports["lightgbm"]["overall"]["mae"] == 0.0
    # each alone model is only right on its own half.
    assert reports["model_a_alone"]["overall"]["mae"] > 0.0
    assert reports["model_b_alone"]["overall"]["mae"] > 0.0


def test_router_reports_keys_present():
    test = _frame(target=[10.0, 20.0], route_id=["1", "2"])
    reports = build_router_reports(
        test, "target", np.array([10.0, 20.0]), np.array([10.0, 20.0]), np.array([True, True])
    )
    assert set(reports) == {"lightgbm", "model_a_alone", "model_b_alone"}


class _Args:
    def __init__(self, target_encoding: bool, fallback_model: bool):
        self.target_encoding = target_encoding
        self.fallback_model = fallback_model


def test_target_encoding_requires_fallback_model():
    with pytest.raises(ValueError, match="requires --fallback-model"):
        validate_target_encoding(_Args(target_encoding=True, fallback_model=False))


def test_target_encoding_with_fallback_model_is_valid():
    validate_target_encoding(_Args(target_encoding=True, fallback_model=True))


def test_no_target_encoding_never_requires_fallback_model():
    validate_target_encoding(_Args(target_encoding=False, fallback_model=False))
