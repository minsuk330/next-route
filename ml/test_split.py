from __future__ import annotations

from datetime import date

import polars as pl
import pytest

from split import split_last_days_per_route


def _frame() -> pl.DataFrame:
    rows = []
    # route A: collected Mon-Wed, route B: Thu-Sat (weekly rotation), route C: one day.
    for route, days in {"A": [1, 2, 3], "B": [4, 5, 6], "C": [6]}.items():
        for day in days:
            rows.append({"route_id": route, "service_date": date(2026, 10, 25 + day), "y": day})
    return pl.DataFrame(rows)


def test_each_route_tests_on_its_own_last_day():
    train, test, policy = split_last_days_per_route(_frame(), last_days=1)

    assert sorted(zip(test["route_id"], test["service_date"])) == [
        ("A", date(2026, 10, 28)),
        ("B", date(2026, 10, 31)),
    ]
    # every route keeps training rows; a single-day route stays train-only.
    assert set(train["route_id"]) == {"A", "B", "C"}
    assert "C" not in set(test["route_id"])
    assert policy["test_route_count"] == 2
    assert policy["train_only_route_count"] == 1


def test_no_route_date_is_in_both_sides():
    train, test, _ = split_last_days_per_route(_frame(), last_days=2)
    key = ["route_id", "service_date"]
    assert train.join(test.select(key).unique(), on=key, how="semi").is_empty()


def test_rejects_non_positive_last_days():
    with pytest.raises(ValueError):
        split_last_days_per_route(_frame(), last_days=0)
