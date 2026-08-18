"""Reproducible check that `api_target_seconds_to_arrival` cannot be used as a feature.

Run this before trusting any metric that involves the API column. It prints the three
independent pieces of evidence documented in docs/api-eta-leakage.md and exits non-zero
when the leakage signature is present, so it can be wired into CI.

    python check_api_eta_leakage.py 2026-07-30
    python check_api_eta_leakage.py 2026-07-30 --data-dir /path/to/nextroute-data
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import polars as pl

from split import load_data_dir


# A per-snapshot forecast re-estimates as the bus moves, so the gap between the forecast
# and the eventual arrival must vary across snapshots of one arrival. Anything under this
# is a single stored constant, not a forecast.
WITHIN_EVENT_STD_TOLERANCE = 1.0

# A genuine forecast is much less accurate 40 minutes out than 2 minutes out. If the
# spread of per-horizon MAE is under this, the "forecast" does not depend on horizon.
HORIZON_SPREAD_TOLERANCE_SECONDS = 20.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Detect the API ETA leakage signature.")
    parser.add_argument("service_date", help="Service date in YYYY-MM-DD format.")
    parser.add_argument("--data-dir", default=None, help="Overrides DATA_DIR from .env.")
    return parser.parse_args()


def evidence_one_value_per_event(dataset: pl.DataFrame) -> bool:
    """Evidence 1: the residual is constant across every snapshot of one arrival."""
    print("\n" + "=" * 78)
    print("EVIDENCE 1 — one stored ETA per arrival, not a per-snapshot forecast")
    print("=" * 78)

    scoped = dataset.with_columns(
        (
            pl.col("corrected_target_seconds_to_arrival")
            - pl.col("api_target_seconds_to_arrival")
        ).alias("residual")
    )
    per_event = scoped.group_by("arrival_label_event_id").agg(
        [pl.len().alias("rows"), pl.col("residual").std().alias("residual_std")]
    )
    spreads = per_event["residual_std"].drop_nulls()
    mean_within = float(spreads.mean() or 0.0)
    overall = float(scoped["residual"].std() or 0.0)

    print(f"arrival events                : {per_event.height:,}")
    print(f"snapshots per event (mean/max): {per_event['rows'].mean():.1f} / {per_event['rows'].max()}")
    print(f"within-event residual std     : {mean_within:.4f} s")
    print(f"overall residual std          : {overall:.2f} s")
    leaked = mean_within < WITHIN_EVENT_STD_TOLERANCE
    print(
        f"\n=> {'LEAKAGE' if leaked else 'ok'}: every snapshot of an arrival carries the same ETA, "
        "so a snapshot\n   40 minutes out holds the same value as one 1 minute out."
        if leaked
        else "\n=> ok: the ETA varies across snapshots, consistent with a live forecast."
    )
    return leaked


def evidence_recorded_after_arrival(label: pl.DataFrame) -> bool:
    """Evidence 2: the stored ETA lands after the arrival it is supposed to predict."""
    print("\n" + "=" * 78)
    print("EVIDENCE 2 — the stored ETA post-dates the arrival")
    print("=" * 78)

    timing = label.filter(
        pl.col("label_arrival_at").is_not_null()
        & pl.col("api_estimated_arrival_at").is_not_null()
        & (pl.col("excluded_from_training") == False)  # noqa: E712 - polars needs the literal
    ).with_columns(
        (pl.col("api_estimated_arrival_at") - pl.col("label_arrival_at"))
        .dt.total_seconds()
        .alias("api_minus_arrival")
    )

    leaked = False
    print(f"{'label_source':<32}{'n':>10}{'p05':>8}{'p50':>8}{'p95':>8}{'max':>8}")
    print("-" * 74)
    for source in sorted(timing["label_source"].unique().to_list()):
        series = timing.filter(pl.col("label_source") == source)["api_minus_arrival"]
        print(
            f"{source:<32}{series.len():>10,}{series.quantile(0.05):>8.0f}"
            f"{series.quantile(0.5):>8.0f}{series.quantile(0.95):>8.0f}{series.max():>8.0f}"
        )
        if float(series.quantile(0.5) or 0.0) > 0:
            leaked = True

    print(
        "\n=> LEAKAGE: a value recorded after the bus already arrived was not knowable\n"
        "   at the snapshot times it is joined to."
        if leaked
        else "\n=> ok: the ETA pre-dates the arrival."
    )
    return leaked


def evidence_flat_across_horizons(dataset: pl.DataFrame) -> bool:
    """Evidence 3: API error does not grow with prediction horizon."""
    print("\n" + "=" * 78)
    print("EVIDENCE 3 — API error is flat across horizons")
    print("=" * 78)

    target = "corrected_target_seconds_to_arrival"
    scoped = dataset.with_columns(
        [
            (pl.col("api_target_seconds_to_arrival") - pl.col(target)).abs().alias("abs_error"),
            pl.when(pl.col(target) < 300)
            .then(pl.lit("1-5m"))
            .when(pl.col(target) < 600)
            .then(pl.lit("5-10m"))
            .when(pl.col(target) < 1200)
            .then(pl.lit("10-20m"))
            .otherwise(pl.lit("20-40m"))
            .alias("bucket"),
        ]
    )
    grouped = scoped.group_by("bucket").agg(
        [pl.len().alias("n"), pl.col("abs_error").mean().alias("mae")]
    )
    order = ["1-5m", "5-10m", "10-20m", "20-40m"]
    values: list[float] = []
    print(f"{'horizon':<10}{'n':>12}{'MAE':>10}")
    print("-" * 32)
    for bucket in order:
        row = grouped.filter(pl.col("bucket") == bucket)
        if row.is_empty():
            continue
        mae = float(row["mae"][0])
        values.append(mae)
        print(f"{bucket:<10}{int(row['n'][0]):>12,}{mae:>10.2f}")

    spread = max(values) - min(values) if values else 0.0
    print(f"\nspread (max - min): {spread:.2f} s")
    leaked = spread < HORIZON_SPREAD_TOLERANCE_SECONDS
    print(
        "\n=> LEAKAGE: a real forecast degrades with horizon. Flat error means the value\n"
        "   is derived from the arrival itself, not predicted ahead of it."
        if leaked
        else "\n=> ok: error grows with horizon, as a real forecast does."
    )
    return leaked


def main() -> int:
    args = parse_args()
    data_dir = Path(args.data_dir).expanduser() if args.data_dir else load_data_dir()
    print(f"[info] data_dir={data_dir}  service_date={args.service_date}")

    dataset_files = sorted(
        (data_dir / "dataset" / f"service_date={args.service_date}").glob("part-*.parquet")
    )
    label_file = data_dir / "bus_label" / f"service_date={args.service_date}" / "data.parquet"
    if not dataset_files:
        print(f"[error] no dataset parts for {args.service_date}", file=sys.stderr)
        return 2
    if not label_file.exists():
        print(f"[error] missing {label_file}", file=sys.stderr)
        return 2

    dataset = pl.read_parquet([str(path) for path in dataset_files]).filter(
        pl.col("corrected_target_seconds_to_arrival").is_not_null()
        & (pl.col("corrected_target_seconds_to_arrival") >= 60)
        & (pl.col("corrected_target_seconds_to_arrival") <= 2400)
        & pl.col("api_target_seconds_to_arrival").is_not_null()
    )
    label = pl.read_parquet(str(label_file))
    print(f"[info] dataset rows={dataset.height:,}  label rows={label.height:,}")

    findings = [
        evidence_one_value_per_event(dataset),
        evidence_recorded_after_arrival(label),
        evidence_flat_across_horizons(dataset),
    ]

    print("\n" + "=" * 78)
    confirmed = sum(findings)
    if confirmed:
        print(f"VERDICT: {confirmed}/3 leakage signatures present.")
        print("api_target_seconds_to_arrival must stay out of the feature set, and any")
        print("'API baseline' computed from it is an oracle, not a comparable baseline.")
        print("=" * 78)
        return 1
    print("VERDICT: no leakage signature found.")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
