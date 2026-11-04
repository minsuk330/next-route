"""Head-to-head: two experiment directories scored on the same held-out rows.

Both models are run the way serve/app.py runs them — raw request fields in,
target-encoding tables and route history features applied as serving does — so the
numbers are what production would see, not what each run's own metrics.json says.

    uv run python compare_models.py \
        --old models/lgbm_2026-06-12_to_2026-08-06_corrected_20260816T060405Z \
        --new data/experiments/lgbm_<run> \
        --test-from-manifest data/experiments/lgbm_<run>/training_manifest.json \
        --rows-per-route 20000

Rows come from the dataset partitions named by the new run's per-route holdout
(``split_policy.test_route_dates``). The old model's routed A/B behaviour is
reproduced: routes in its ``training_route_categories`` go to model A, the rest to
model B when present, else they count as UNSUPPORTED (excluded, reported).
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import lightgbm as lgb
import numpy as np
import pandas as pd
import polars as pl

from feature_engineering import SERVABLE_ENCODING_SPECS, compute_serving_features, encoding_name
from realtime_features import REALTIME_FEATURES
from route_pace import ROUTE_PACE_FEATURES
from split import HORIZON_BUCKETS, add_horizon_bucket, load_data_dir, scan_dataset

TARGET = "corrected_target_seconds_to_arrival"
RAW_FEATURE_NAMES = [
    "current_section_order", "section_progress", "current_section_distance",
    "current_full_section_distance", "next_stop_time", "last_stop_time", "congestion",
    "gps_x", "gps_y", "target_seq", "remaining_stop_count", "hour_of_day", "minute_of_day",
    "day_of_week", "is_weekend", "is_holiday", "is_pre_holiday", "is_post_holiday",
    "is_commute_morning", "is_commute_evening", "is_late_night", "temperature_c",
    "precipitation_mm", "humidity_pct", "wind_speed_mps", "is_raining", "is_snowing",
]


class Model:
    def __init__(self, path: Path):
        self.dir = path
        self.manifest = json.loads((path / "training_manifest.json").read_text())
        self.booster = lgb.Booster(model_file=str(path / "model.txt"))
        self.features = list(self.booster.feature_name())
        self.routes = set(str(r) for r in self.manifest.get("training_route_categories") or [])
        self.te = bool(self.manifest.get("target_encoding_enabled"))
        self.booster_b = None
        if (path / "model_b.txt").exists() and self.manifest.get("router_enabled"):
            self.booster_b = lgb.Booster(model_file=str(path / "model_b.txt"))
        if self.te:
            tables_dir = path / "encoding_tables"
            policy = self.manifest["feature_policy"]
            self.profile = pl.read_parquet(tables_dir / "route_profile.parquet")
            self.tables = {
                encoding_name(k, v): pl.read_parquet(tables_dir / f"{encoding_name(k, v)}.parquet")
                for k, v in SERVABLE_ENCODING_SPECS
            }
            self.global_pace = float(policy["encoding_serving_stats"]["global_pace"])
            self.smoothing = float(policy["target_encoding"]["smoothing"])
        self.horizons = {str(k): int(v) for k, v in (self.manifest.get("route_horizon_seconds") or {}).items()}
        self.default_horizon = int(self.manifest.get("default_horizon_seconds") or 2400)

    def predict(self, rows: pl.DataFrame) -> tuple[np.ndarray, np.ndarray]:
        """(prediction, model_tag) per row; tag 0 = A, 1 = B, -1 = unsupported."""
        raw = rows.select([pl.col(c).cast(pl.Float64) for c in RAW_FEATURE_NAMES]).with_columns(
            rows["route_id"].cast(pl.Utf8)
        )
        for c in [*REALTIME_FEATURES, *ROUTE_PACE_FEATURES]:
            if c in self.features:
                raw = raw.with_columns(rows[c].cast(pl.Float64).alias(c))
        seen = rows["route_id"].cast(pl.Utf8).is_in(list(self.routes)).to_numpy()
        pred = np.full(rows.height, np.nan)
        tag = np.full(rows.height, -1)
        if seen.any():
            sub = raw.filter(pl.Series(seen))
            if self.te:
                frame = compute_serving_features(sub, self.profile, self.tables, self.global_pace, self.smoothing)
                x = frame.select(self.features).to_pandas()
            else:
                x = sub.select(self.features).to_pandas()
                x["route_id"] = pd.Categorical(x["route_id"], categories=self.booster.pandas_categorical[0])
            pred[seen] = self.booster.predict(x)
            tag[seen] = 0
        if self.booster_b is not None and (~seen).any():
            sub = raw.filter(pl.Series(~seen))
            x = sub.select(list(self.booster_b.feature_name())).to_pandas()
            pred[~seen] = self.booster_b.predict(x)
            tag[~seen] = 1
        return pred, tag


def load_test_rows(data_dir: Path, test_route_dates: list[dict], rows_per_route: int, seed: int) -> pl.DataFrame:
    by_date: dict[str, list[str]] = {}
    for item in test_route_dates:
        by_date.setdefault(item["service_date"], []).append(str(item["route_id"]))
    parts = []
    for service_date, routes in sorted(by_date.items()):
        from datetime import date
        frame = (
            scan_dataset(data_dir, date.fromisoformat(service_date))
            .filter(pl.col("route_id").cast(pl.Utf8).is_in(routes) & pl.col(TARGET).is_not_null())
            .collect(engine="streaming")
        )
        parts.append(
            frame.group_by("route_id", maintain_order=True)
            .map_groups(lambda g: g.sample(min(rows_per_route, g.height), seed=seed))
        )
        print(f"[rows] {service_date}: {parts[-1].height:,} rows / {len(routes)} routes", flush=True)
    return pl.concat(parts)


def report(name: str, frame: pl.DataFrame, pred: np.ndarray, tag: np.ndarray) -> dict[str, Any]:
    scored = frame.with_columns(pl.Series("_pred", pred), pl.Series("_tag", tag))
    scored = scored.filter(pl.col("_tag") >= 0).with_columns(
        (pl.col("_pred") - pl.col(TARGET)).abs().alias("_ae")
    )
    scored = add_horizon_bucket(scored, TARGET)
    out: dict[str, Any] = {
        "model": name,
        "rows_scored": scored.height,
        "rows_unsupported": int((tag < 0).sum()),
        "mae": round(float(scored["_ae"].mean()), 1) if scored.height else None,
        "p50": round(float(scored["_ae"].median()), 1) if scored.height else None,
        "p90": round(float(scored["_ae"].quantile(0.9)), 1) if scored.height else None,
        "by_horizon": {
            b: round(float(scored.filter(pl.col("horizon_bucket") == b)["_ae"].mean()), 1)
            for b in HORIZON_BUCKETS
            if scored.filter(pl.col("horizon_bucket") == b).height
        },
        "by_model": {
            {0: "A", 1: "B"}[int(t)]: {
                "rows": int((scored["_tag"] == t).sum()),
                "mae": round(float(scored.filter(pl.col("_tag") == t)["_ae"].mean()), 1),
            }
            for t in scored["_tag"].unique().to_list()
        },
    }
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--old", required=True, type=Path)
    parser.add_argument("--new", required=True, type=Path)
    parser.add_argument("--test-from-manifest", required=True, type=Path)
    parser.add_argument("--rows-per-route", type=int, default=20000)
    parser.add_argument("--max-target-seconds", type=int, default=2400,
                        help="Score only rows within this remaining time (both models' common ground).")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    manifest = json.loads(args.test_from_manifest.read_text())
    test_route_dates = manifest["split_policy"]["test_route_dates"]
    rows = load_test_rows(load_data_dir(), test_route_dates, args.rows_per_route, args.seed)
    rows = rows.filter(pl.col(TARGET) <= args.max_target_seconds)
    print(f"[rows] total {rows.height:,} (target <= {args.max_target_seconds}s), routes {rows['route_id'].n_unique()}")

    results = {}
    for name, path in (("old", args.old), ("new", args.new)):
        model = Model(path)
        pred, tag = model.predict(rows)
        results[name] = report(f"{name}:{path.name}", rows, pred, tag)
        # also: how the old model fares on routes it trained on vs not
        seen = rows["route_id"].cast(pl.Utf8).is_in(list(model.routes)).to_numpy()
        results[name]["routes_trained"] = len(model.routes)
        results[name]["rows_on_trained_routes"] = int(seen.sum())
        print(json.dumps(results[name], ensure_ascii=False), flush=True)

    old_routes = Model(args.old).routes
    on_old = rows["route_id"].cast(pl.Utf8).is_in(list(old_routes)).to_numpy()
    if on_old.any() and (~on_old).any():
        for name, path in (("old", args.old), ("new", args.new)):
            model = Model(path)
            for label, mask in (("old_trained_routes", on_old), ("old_untrained_routes", ~on_old)):
                sub = rows.filter(pl.Series(mask))
                pred, tag = model.predict(sub)
                results[f"{name}@{label}"] = report(f"{name}@{label}", sub, pred, tag)
                print(json.dumps(results[f"{name}@{label}"], ensure_ascii=False), flush=True)

    if args.out:
        args.out.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
