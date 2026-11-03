"""Per-route prediction horizon: how far ahead each route's predictions are reliable.

A single global 40-minute horizon fits no route well. A short circular route never
has a bus 40 minutes away, while a long trunk route is still predictable an hour
out. Two things bound a route's horizon:

1. trip length — the trip-consistent label join means a snapshot can only be
   paired with an arrival later in the same trip, so a route never has targets
   beyond its trip duration;
2. accuracy — in 10-minute bins of remaining time, the first bin whose p90 absolute
   error exceeds the limit ends the horizon. Bins without enough test rows are not
   evidence of failure and do not restrict.

serve/app.py reads the per-route caps from the training manifest and answers
BEYOND_HORIZON for a prediction past its route's cap, so the app falls back to the
timetable instead of showing a number nobody can stand behind.
"""

from __future__ import annotations

import math
from typing import Any

import polars as pl

DEFAULT_BIN_SECONDS = 600
DEFAULT_P90_LIMIT_SECONDS = 420
DEFAULT_MIN_ROWS = 100
TRIP_LENGTH_QUANTILE = 0.99


def route_horizons(
    scored: pl.DataFrame,
    target_column: str,
    prediction_column: str,
    train_targets: pl.DataFrame,
    max_target_seconds: int,
    bin_seconds: int = DEFAULT_BIN_SECONDS,
    p90_limit_seconds: float = DEFAULT_P90_LIMIT_SECONDS,
    min_rows: int = DEFAULT_MIN_ROWS,
) -> tuple[dict[str, int], dict[str, Any]]:
    """Return ({route_id: horizon_seconds}, detail).

    ``scored``: test rows with route_id, the target and the prediction columns.
    ``train_targets``: training rows with route_id and the target column (trip length).
    Every route in either frame gets a cap; a route with no test evidence is capped by
    trip length only. Caps are multiples of ``bin_seconds``, at least one bin, at most
    ``max_target_seconds``.
    """
    stats = (
        scored.filter(pl.col(prediction_column).is_not_null() & pl.col(target_column).is_not_null())
        .with_columns(
            pl.col("route_id").cast(pl.Utf8),
            (pl.col(prediction_column) - pl.col(target_column)).abs().alias("_abs_error"),
            ((pl.col(target_column) // bin_seconds) * bin_seconds).cast(pl.Int64).alias("_bin"),
        )
        .group_by("route_id", "_bin")
        .agg(pl.len().alias("n"), pl.col("_abs_error").quantile(0.9).alias("p90"))
    )
    bins_by_route: dict[str, dict[int, tuple[int, float]]] = {}
    for row in stats.iter_rows(named=True):
        bins_by_route.setdefault(row["route_id"], {})[int(row["_bin"])] = (int(row["n"]), float(row["p90"]))

    trip_length = {
        row["route_id"]: float(row["q"])
        for row in train_targets.filter(pl.col(target_column).is_not_null())
        .with_columns(pl.col("route_id").cast(pl.Utf8))
        .group_by("route_id")
        .agg(pl.col(target_column).quantile(TRIP_LENGTH_QUANTILE).alias("q"))
        .iter_rows(named=True)
    }

    caps: dict[str, int] = {}
    detail: dict[str, Any] = {}
    for route_id in sorted(set(bins_by_route) | set(trip_length)):
        bins = bins_by_route.get(route_id, {})
        evidence_cap = max_target_seconds
        failed_bin = None
        thin_from = None
        for start in range(0, max_target_seconds, bin_seconds):
            n, p90 = bins.get(start, (0, math.nan))
            if n < min_rows:
                thin_from = start  # no evidence past here, so no restriction from here
                break
            if p90 > p90_limit_seconds:
                evidence_cap = start
                failed_bin = start
                break
        trip_cap = max_target_seconds
        if route_id in trip_length:
            trip_cap = int(math.ceil(trip_length[route_id] / bin_seconds) * bin_seconds)
        cap = min(evidence_cap, trip_cap, max_target_seconds)
        cap = max(cap, bin_seconds)
        caps[route_id] = int(cap)
        detail[route_id] = {
            "horizon_seconds": int(cap),
            "evidence_cap_seconds": int(evidence_cap),
            "trip_cap_seconds": int(trip_cap),
            "failed_bin_start": failed_bin,
            "thin_from_bin_start": thin_from,
            "bins": {str(k): {"n": v[0], "p90": round(v[1], 1)} for k, v in sorted(bins.items())},
        }
    return caps, detail


def summarize(caps: dict[str, int], default_seconds: int) -> dict[str, Any]:
    values = sorted(caps.values())
    if not values:
        return {"route_count": 0}
    return {
        "route_count": len(values),
        "min_seconds": values[0],
        "median_seconds": values[len(values) // 2],
        "max_seconds": values[-1],
        "routes_below_default": sum(v < default_seconds for v in values),
        "routes_at_default": sum(v >= default_seconds for v in values),
    }
