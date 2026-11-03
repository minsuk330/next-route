from __future__ import annotations

from datetime import date, timedelta

import polars as pl
import pytest

from split import split_last_days_per_route


def _frame() -> pl.DataFrame:
    rows = []
    # 2026-10-26 is a Monday. route A: Mon-Wed, route B: Thu-Sun (one Sat, one Sun),
    # route C: one day, route D: a full Mon-Sun rotation week.
    for route, days in {"A": [0, 1, 2], "B": [3, 4, 5, 6], "C": [6], "D": list(range(7))}.items():
        for day in days:
            rows.append({"route_id": route, "service_date": date(2026, 10, 26) + timedelta(days=day), "y": day})
    return pl.DataFrame(rows)


def test_each_route_tests_on_its_last_spare_day():
    train, test, policy = split_last_days_per_route(_frame(), last_days=1)

    assert sorted(zip(test["route_id"], test["service_date"])) == [
        ("A", date(2026, 10, 28)),  # Wed: weekdays are plentiful
        ("B", date(2026, 10, 30)),  # Fri, not the Sunday — B has only one Sat and one Sun
        ("D", date(2026, 10, 30)),  # Fri for the same reason
    ]
    # every route keeps training rows; a single-day route stays train-only.
    assert set(train["route_id"]) == {"A", "B", "C", "D"}
    assert "C" not in set(test["route_id"])
    assert policy["test_route_count"] == 3
    assert policy["train_only_route_count"] == 1
    assert policy["day_type_protected"] is True


def test_saturday_and_sunday_always_stay_in_training():
    train, _, _ = split_last_days_per_route(_frame(), last_days=2)
    for route in ("B", "D"):
        dates = set(train.filter(pl.col("route_id") == route)["service_date"])
        assert date(2026, 10, 31) in dates and date(2026, 11, 1) in dates


def test_no_route_date_is_in_both_sides():
    train, test, _ = split_last_days_per_route(_frame(), last_days=2)
    key = ["route_id", "service_date"]
    assert train.join(test.select(key).unique(), on=key, how="semi").is_empty()


def test_rejects_non_positive_last_days():
    with pytest.raises(ValueError):
        split_last_days_per_route(_frame(), last_days=0)
