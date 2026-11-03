from __future__ import annotations

import polars as pl

from route_horizon import route_horizons, summarize


def _scored(route: str, rows: list[tuple[int, float]]) -> pl.DataFrame:
    """rows: (target_seconds, abs_error) — prediction = target + abs_error."""
    return pl.DataFrame(
        {
            "route_id": [route] * len(rows),
            "y": [t for t, _ in rows],
            "pred": [t + e for t, e in rows],
        }
    )


def _bin(target: int, n: int, p90: float) -> list[tuple[int, float]]:
    # n rows in one bin: 85% with small error, 15% with error p90 (so quantile(0.9) == p90)
    small = int(n * 0.85)
    return [(target, 1.0)] * small + [(target, p90)] * (n - small)


def _train(route: str, max_target: int) -> pl.DataFrame:
    return pl.DataFrame({"route_id": [route] * 100, "y": list(range(60, max_target, max(1, (max_target - 60) // 100)))[:100]})


def test_accuracy_ends_horizon_at_first_failing_bin():
    rows = _bin(300, 200, 100) + _bin(900, 200, 200) + _bin(1500, 200, 500) + _bin(2100, 200, 150)
    caps, detail = route_horizons(_scored("A", rows), "y", "pred", _train("A", 3000), 3600)
    assert caps["A"] == 1200  # 20-30 min bin has p90 500s > 420s
    assert detail["A"]["failed_bin_start"] == 1200


def test_thin_bins_do_not_restrict():
    rows = _bin(300, 200, 100) + _bin(900, 50, 900)  # second bin: too few rows to count
    caps, detail = route_horizons(_scored("A", rows), "y", "pred", _train("A", 3000), 3600)
    assert detail["A"]["thin_from_bin_start"] == 600
    assert caps["A"] == 3000  # only the trip length limits it


def test_trip_length_caps_short_route():
    rows = _bin(300, 200, 100)
    caps, _ = route_horizons(_scored("A", rows), "y", "pred", _train("A", 840), 3600)
    assert caps["A"] == 1200  # p99 ~ 830s -> rounded up to the next 10-min bin


def test_route_without_test_rows_gets_trip_cap_only():
    caps, detail = route_horizons(_scored("A", _bin(300, 200, 100)), "y", "pred", _train("B", 1500), 3600)
    assert caps["B"] == 1800
    assert detail["B"]["bins"] == {}


def test_cap_never_below_one_bin_and_never_above_max():
    rows = _bin(300, 200, 999)  # fails in the first bin
    caps, _ = route_horizons(_scored("A", rows), "y", "pred", _train("A", 9000), 2400)
    assert caps["A"] == 600
    rows = _bin(300, 200, 10) + _bin(900, 200, 10) + _bin(1500, 200, 10) + _bin(2100, 200, 10)
    caps, _ = route_horizons(_scored("A", rows), "y", "pred", _train("A", 9000), 2400)
    assert caps["A"] == 2400


def test_summary_counts_capped_routes():
    s = summarize({"A": 1200, "B": 2400, "C": 600}, 2400)
    assert s == {
        "route_count": 3,
        "min_seconds": 600,
        "median_seconds": 1200,
        "max_seconds": 2400,
        "routes_below_default": 2,
        "routes_at_default": 1,
    }
