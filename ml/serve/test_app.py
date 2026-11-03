"""Tests for the serving app. Builds a tiny real LightGBM model so the
pandas_categorical contract and feature ordering are exercised end to end.

Run: uv run --extra serve --extra serve-dev pytest ml/serve/test_app.py
"""

from __future__ import annotations

import argparse
import importlib
import json
from pathlib import Path

import lightgbm as lgb
import pandas as pd
import polars as pl
import pytest
from fastapi.testclient import TestClient

from feature_engineering import (
    SERVABLE_ENCODING_SPECS,
    SERVABLE_ROUTE_AGNOSTIC_SPECS,
    build_feature_frames,
    persist_encoding_artifacts,
)

FEATURES = [
    "current_section_order",
    "section_progress",
    "current_section_distance",
    "current_full_section_distance",
    "next_stop_time",
    "last_stop_time",
    "congestion",
    "gps_x",
    "gps_y",
    "target_seq",
    "remaining_stop_count",
    "hour_of_day",
    "minute_of_day",
    "day_of_week",
    "is_weekend",
    "is_holiday",
    "is_pre_holiday",
    "is_post_holiday",
    "is_commute_morning",
    "is_commute_evening",
    "is_late_night",
    "temperature_c",
    "precipitation_mm",
    "humidity_pct",
    "wind_speed_mps",
    "is_raining",
    "is_snowing",
    "route_id",
]
ROUTES = ["143", "272", "160"]


def _build_model(model_dir: Path) -> None:
    rows = []
    for i in range(300):
        route = ROUTES[i % len(ROUTES)]
        rows.append(
            {
                "current_section_order": i % 20,
                "section_progress": (i % 10) / 10.0,
                "current_section_distance": float(i * 3 % 500),
                "current_full_section_distance": 500.0,
                "next_stop_time": i % 60,
                "last_stop_time": i % 40,
                "congestion": i % 4,
                "gps_x": 127.0 + (i % 10) / 100.0,
                "gps_y": 37.5 + (i % 10) / 100.0,
                "target_seq": (i % 20) + 3,
                "remaining_stop_count": 3,
                "hour_of_day": i % 24,
                "minute_of_day": i % 60,
                "day_of_week": (i % 7) + 1,
                "is_weekend": 1 if (i % 7) + 1 in (6, 7) else 0,
                "is_holiday": i % 2,
                "is_pre_holiday": (i + 1) % 2,
                "is_post_holiday": i % 3 == 0,
                "is_commute_morning": 1 if (i % 24) in (7, 8) else 0,
                "is_commute_evening": 1 if (i % 24) in (18, 19) else 0,
                "is_late_night": 1 if (i % 24) >= 23 or (i % 24) < 5 else 0,
                "temperature_c": float(i % 35) - 5.0,
                "precipitation_mm": float(i % 5),
                "humidity_pct": float(40 + i % 60),
                "wind_speed_mps": float(i % 8),
                "is_raining": 1 if i % 5 else 0,
                "is_snowing": 1 if i % 9 == 0 else 0,
                "route_id": route,
                "y": 120 + (i % 600),
            }
        )
    frame = pd.DataFrame(rows)
    frame["route_id"] = frame["route_id"].astype("category")
    x = frame[FEATURES]
    y = frame["y"]
    booster = lgb.train(
        {"objective": "mae", "num_leaves": 7, "verbose": -1},
        lgb.Dataset(x, y, categorical_feature=["route_id"]),
        num_boost_round=5,
    )
    model_dir.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(model_dir / "model.txt"))
    (model_dir / "training_manifest.json").write_text(
        json.dumps(
            {
                "schema_version": 2,
                "with_api_feature": False,
                "feature_list": FEATURES,
                "model_version": "test-model-v1",
            }
        )
    )


RAW_FEATURES = [f for f in FEATURES if f != "route_id"]


def _client(monkeypatch, model_path: str | None) -> TestClient:
    import serve.app as app_module

    importlib.reload(app_module)
    if model_path is None:
        monkeypatch.delenv("ML_MODEL_PATH", raising=False)
    else:
        monkeypatch.setenv("ML_MODEL_PATH", model_path)
    return TestClient(app_module.app)


def _item(request_id: str, route: str = "143", **overrides):
    features = {f: 1.0 for f in FEATURES}
    features["route_id"] = route
    features.update(overrides)
    return {"request_id": request_id, "features": features}


@pytest.fixture()
def model_dir(tmp_path) -> Path:
    d = tmp_path / "experiment"
    _build_model(d)
    return d


def test_health_ok_when_model_loaded(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.get("/health")
        assert res.status_code == 200
        body = res.json()
        assert body["model_version"] == "test-model-v1"
        # schema2 contract = 28 features (16 base + 12 calendar/weather)
        assert body["feature_count"] == 28
        assert len(FEATURES) == 28


def test_health_503_without_model(monkeypatch):
    with _client(monkeypatch, None) as client:
        res = client.get("/health")
        assert res.status_code == 503
        assert res.json()["status"] == "unavailable"


def test_metadata_exposes_route_categories(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.get("/metadata")
        assert res.status_code == 200
        body = res.json()
        # manifest에 training_route_categories 없음 → model categorical로 폴백
        assert body["route_count"] == len(ROUTES)
        assert sorted(body["route_categories"]) == sorted(ROUTES)
        assert body["model_route_count"] == len(ROUTES)
        # schema2 feature_list = 28 (booster feature order)
        assert body["feature_list"] == FEATURES
        assert body["schema_version"] == 2


def _set_training_routes(model_dir: Path, trained: list[str]) -> None:
    manifest_path = model_dir / "training_manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["training_route_categories"] = trained
    manifest_path.write_text(json.dumps(manifest))


def test_metadata_uses_training_route_categories_when_present(monkeypatch, model_dir):
    # 모델은 143/272/160 모두 categorical이지만 train split엔 143/272만 있었던 경우
    _set_training_routes(model_dir, ["143", "272"])
    with _client(monkeypatch, str(model_dir)) as client:
        body = client.get("/metadata").json()
        assert sorted(body["route_categories"]) == ["143", "272"]
        assert body["route_count"] == 2
        # booster categorical은 여전히 3개 → 미학습 route 존재 신호
        assert body["model_route_count"] == len(ROUTES)


def test_predict_isolates_untrained_category_route(monkeypatch, model_dir):
    # 160은 model categorical엔 있지만 학습 안 됨 → UNSUPPORTED, 학습된 143은 정상 예측
    _set_training_routes(model_dir, ["143", "272"])
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post(
            "/predict",
            json={"items": [_item("ok", route="143"), _item("untrained", route="160")]},
        )
        assert res.status_code == 200
        by_id = {r["request_id"]: r for r in res.json()["results"]}
        assert by_id["ok"]["status"] == "AVAILABLE"
        assert isinstance(by_id["ok"]["seconds_to_arrival"], float)
        assert by_id["untrained"]["status"] == "UNSUPPORTED_ROUTE"
        assert by_id["untrained"]["seconds_to_arrival"] is None


def test_metadata_503_without_model(monkeypatch):
    with _client(monkeypatch, None) as client:
        res = client.get("/metadata")
        assert res.status_code == 503


def test_predict_normal(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post("/predict", json={"items": [_item("r1"), _item("r2", route="272")]})
        assert res.status_code == 200
        results = res.json()["results"]
        assert [r["request_id"] for r in results] == ["r1", "r2"]
        assert all(r["status"] == "AVAILABLE" for r in results)
        assert all(isinstance(r["seconds_to_arrival"], float) for r in results)


def test_predict_unsupported_route_isolated(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post(
            "/predict",
            json={"items": [_item("r1"), _item("bad", route="99999")]},
        )
        assert res.status_code == 200
        by_id = {r["request_id"]: r for r in res.json()["results"]}
        assert by_id["r1"]["status"] == "AVAILABLE"
        assert by_id["bad"]["status"] == "UNSUPPORTED_ROUTE"
        assert by_id["bad"]["seconds_to_arrival"] is None


def test_predict_null_numeric_allowed(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post(
            "/predict", json={"items": [_item("r1", congestion=None, gps_x=None)]}
        )
        assert res.status_code == 200
        assert res.json()["results"][0]["status"] == "AVAILABLE"


def test_predict_missing_feature_422(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        item = _item("r1")
        del item["features"]["gps_x"]
        res = client.post("/predict", json={"items": [item]})
        assert res.status_code == 422


def test_predict_duplicate_request_id_422(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post("/predict", json={"items": [_item("dup"), _item("dup")]})
        assert res.status_code == 422


def test_predict_503_without_model(monkeypatch):
    with _client(monkeypatch, None) as client:
        res = client.post("/predict", json={"items": [_item("r1")]})
        assert res.status_code == 503


# --- target encoding (model A route-keyed te_* features, no route_id categorical) --


def _synthetic_rows() -> list[dict]:
    rows = []
    for i in range(400):
        route = ROUTES[i % len(ROUTES)]
        rows.append(
            {
                "route_id": route,
                "current_section_order": i % 20,
                "section_progress": (i % 10) / 10.0,
                "current_section_distance": float(i * 3 % 500),
                "current_full_section_distance": 500.0,
                "next_stop_time": i % 60,
                "last_stop_time": i % 40,
                "congestion": float(i % 4),
                "gps_x": 127.0 + (i % 10) / 100.0,
                "gps_y": 37.5 + (i % 10) / 100.0,
                "target_seq": (i % 20) + 3,
                "remaining_stop_count": 3 + (i % 5),
                "hour_of_day": i % 24,
                "minute_of_day": i % 60,
                "day_of_week": (i % 7) + 1,
                "is_weekend": 1 if (i % 7) + 1 in (6, 7) else 0,
                "is_holiday": i % 2,
                "is_pre_holiday": (i + 1) % 2,
                "is_post_holiday": 1 if i % 3 == 0 else 0,
                "is_commute_morning": 1 if (i % 24) in (7, 8) else 0,
                "is_commute_evening": 1 if (i % 24) in (18, 19) else 0,
                "is_late_night": 1 if (i % 24) >= 23 or (i % 24) < 5 else 0,
                "temperature_c": float(i % 35) - 5.0,
                "precipitation_mm": float(i % 5),
                "humidity_pct": float(40 + i % 60),
                "wind_speed_mps": float(i % 8),
                "is_raining": 1 if i % 5 else 0,
                "is_snowing": 1 if i % 9 == 0 else 0,
                "target_seconds": 120.0 + (i % 600),
            }
        )
    return rows


def _build_target_encoding_model(model_dir: Path) -> tuple[list[dict], list[float]]:
    """Trains a real target-encoding model the same way
    train.py's --target-encoding path does, using feature_engineering directly, so
    the test exercises the actual training-side artifact shape. Returns the raw
    test-split rows (as /predict would receive them) and model A's own offline
    predictions on those same rows, for a parity check against served predictions.
    """
    frame = pl.DataFrame(_synthetic_rows())
    train_pl, test_pl = frame[:320], frame[320:]

    args = argparse.Namespace(
        target_column="target_seconds",
        serving_parity=True,
        route_agnostic=False,
        api_oracle=False,
        no_target_encoding=False,
        full_data_encoding=False,
        data_dir=None,
        encoding_dates=[],
        te_folds=5,
        te_smoothing=50.0,
        seed=42,
        numeric_features=RAW_FEATURES,
        encoding_specs=SERVABLE_ENCODING_SPECS,
        route_agnostic_specs=SERVABLE_ROUTE_AGNOSTIC_SPECS,
    )
    train_frame, test_frame, model_a_columns, feature_policy = build_feature_frames(
        train_pl, test_pl, args
    )

    x_train = train_frame.select(model_a_columns).to_pandas()
    y_train = train_frame["target_seconds"].to_pandas()
    x_test = test_frame.select(model_a_columns).to_pandas()

    booster = lgb.train(
        {"objective": "mae", "num_leaves": 7, "verbose": -1},
        lgb.Dataset(x_train, y_train),
        num_boost_round=5,
    )
    test_predictions = [float(p) for p in booster.predict(x_test)]
    model_dir.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(model_dir / "model.txt"))

    safe_policy = persist_encoding_artifacts(model_dir, feature_policy)
    training_route_categories = sorted(set(train_pl["route_id"].to_list()))

    manifest = {
        "schema_version": 2,
        "with_api_feature": False,
        "feature_list": model_a_columns,
        "model_version": "test-te-model-v1",
        "target_encoding_enabled": True,
        "training_route_categories": training_route_categories,
        "feature_policy": safe_policy,
    }
    (model_dir / "training_manifest.json").write_text(json.dumps(manifest))

    return test_pl.to_dicts(), test_predictions


@pytest.fixture()
def target_encoding_model_dir(tmp_path) -> tuple[Path, list[dict], list[float]]:
    d = tmp_path / "te_experiment"
    rows, predictions = _build_target_encoding_model(d)
    return d, rows, predictions


def test_target_encoding_metadata_has_no_route_id_in_feature_list(
    monkeypatch, target_encoding_model_dir
):
    model_dir, _rows, _predictions = target_encoding_model_dir
    with _client(monkeypatch, str(model_dir)) as client:
        body = client.get("/metadata").json()
        assert body["target_encoding_enabled"] is True
        assert "route_id" not in body["feature_list"]
        assert any(f.startswith("te_pace__") for f in body["feature_list"])


def test_target_encoding_predict_matches_offline_predictions(
    monkeypatch, target_encoding_model_dir
):
    """The whole point of compute_serving_features: a live request built from only
    the raw fields (no te_*/derived columns) must reproduce, via the persisted
    encoding tables, the exact same features — and thus the exact same prediction —
    that build_feature_frames computed for this row at training/eval time."""
    model_dir, rows, predictions = target_encoding_model_dir
    with _client(monkeypatch, str(model_dir)) as client:
        items = [
            {"request_id": f"r{i}", "features": {k: v for k, v in row.items() if k != "target_seconds"}}
            for i, row in enumerate(rows)
        ]
        res = client.post("/predict", json={"items": items})
        assert res.status_code == 200
        results = res.json()["results"]
        assert all(r["status"] == "AVAILABLE" for r in results)
        served = [r["seconds_to_arrival"] for r in results]
        for served_value, offline_value in zip(served, predictions):
            assert served_value == pytest.approx(offline_value, abs=1e-4)


def test_target_encoding_unseen_route_is_unsupported(
    monkeypatch, target_encoding_model_dir
):
    model_dir, rows, _predictions = target_encoding_model_dir
    with _client(monkeypatch, str(model_dir)) as client:
        item = {k: v for k, v in rows[0].items() if k != "target_seconds"}
        item["route_id"] = "999999"
        res = client.post(
            "/predict", json={"items": [{"request_id": "r0", "features": item}]}
        )
        assert res.status_code == 200
        result = res.json()["results"][0]
        assert result["status"] == "UNSUPPORTED_ROUTE"
        assert result["seconds_to_arrival"] is None


def test_target_encoding_missing_raw_feature_422(monkeypatch, target_encoding_model_dir):
    model_dir, rows, _predictions = target_encoding_model_dir
    with _client(monkeypatch, str(model_dir)) as client:
        item = {k: v for k, v in rows[0].items() if k != "target_seconds"}
        del item["congestion"]
        res = client.post(
            "/predict", json={"items": [{"request_id": "r0", "features": item}]}
        )
        assert res.status_code == 422


def test_plain_model_isolates_untrained_route(monkeypatch, model_dir):
    with _client(monkeypatch, str(model_dir)) as client:
        assert "router_enabled" not in client.get("/metadata").json()
        res = client.post(
            "/predict",
            json={"items": [_item("r1", route="143"), _item("bad", route="99999")]},
        )
        by_id = {r["request_id"]: r for r in res.json()["results"]}
        assert by_id["r1"]["status"] == "AVAILABLE"
        assert by_id["bad"]["status"] == "UNSUPPORTED_ROUTE"


# --- real-time (route history) features -------------------------------------

from datetime import datetime, timezone  # noqa: E402

from realtime_features import REALTIME_FEATURES, compute_realtime_features  # noqa: E402

RT_FEATURES = [*RAW_FEATURES, *REALTIME_FEATURES, "route_id"]


def _build_realtime_model(model_dir: Path) -> lgb.Booster:
    rows = []
    for i in range(300):
        row = {f: float((i * 7 + k) % 50) for k, f in enumerate(RAW_FEATURES)}
        for k, f in enumerate(REALTIME_FEATURES):
            row[f] = float((i * 3 + k) % 400) if i % 4 else float("nan")
        row["route_id"] = ROUTES[i % len(ROUTES)]
        row["y"] = 120 + (i % 600)
        rows.append(row)
    frame = pd.DataFrame(rows)
    frame["route_id"] = frame["route_id"].astype("category")
    booster = lgb.train(
        {"objective": "mae", "num_leaves": 7, "verbose": -1},
        lgb.Dataset(frame[RT_FEATURES], frame["y"], categorical_feature=["route_id"]),
        num_boost_round=20,
    )
    model_dir.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(model_dir / "model.txt"))
    (model_dir / "training_manifest.json").write_text(
        json.dumps(
            {
                "schema_version": 2,
                "with_api_feature": False,
                "feature_list": RT_FEATURES,
                "model_version": "test-rt-model-v1",
                "realtime_features": REALTIME_FEATURES,
            }
        )
    )
    return booster


@pytest.fixture()
def realtime_model_dir(tmp_path) -> tuple[Path, lgb.Booster]:
    d = tmp_path / "rt_experiment"
    return d, _build_realtime_model(d)


T0 = datetime(2026, 10, 30, 9, 0, 0)


def _epoch(dt: datetime) -> int:
    return int(dt.replace(tzinfo=timezone.utc).timestamp())


def _route_history() -> list[dict]:
    """Leader L one section per minute from 09:00; follower F every 2 minutes from 09:10."""
    obs = [{"vehicle_id": "L", "section_order": k, "snapshot_at": _epoch(T0.replace(minute=k - 1))}
           for k in range(1, 13)]
    obs += [{"vehicle_id": "F", "section_order": k, "snapshot_at": _epoch(T0.replace(minute=10 + 2 * (k - 1)))}
            for k in range(1, 4)]
    return obs


def _rt_item(request_id: str, with_identity: bool = True) -> dict:
    item = _item(request_id, route="143", current_section_order=3, target_seq=6)
    if with_identity:
        item["vehicle_id"] = "F"
        item["snapshot_at"] = _epoch(T0.replace(minute=14))
    return item


def _offline_prediction(booster: lgb.Booster, item: dict, history: list[dict] | None) -> float:
    raw = {f: [float(item["features"][f])] for f in RAW_FEATURES}
    if history is None:
        rt = {f: [float("nan")] for f in REALTIME_FEATURES}
    else:
        hist = pl.DataFrame(
            {
                "route_id": ["143"] * len(history),
                "vehicle_identity": [h["vehicle_id"] for h in history],
                "snapshot_at": [datetime.fromtimestamp(h["snapshot_at"], tz=timezone.utc).replace(tzinfo=None) for h in history],
                "current_section_order": [h["section_order"] for h in history],
            }
        )
        query = pl.DataFrame(
            {
                "route_id": ["143"],
                "vehicle_identity": [item["vehicle_id"]],
                "snapshot_at": [datetime.fromtimestamp(item["snapshot_at"], tz=timezone.utc).replace(tzinfo=None)],
                "current_section_order": [3],
                "target_seq": [6],
            }
        )
        rt = compute_realtime_features(hist, query).to_dict(as_series=False)
    frame = pd.DataFrame({**raw, **rt, "route_id": ["143"]})
    frame["route_id"] = pd.Categorical(frame["route_id"], categories=booster.pandas_categorical[0])
    return float(booster.predict(frame[RT_FEATURES])[0])


def test_realtime_metadata(monkeypatch, realtime_model_dir):
    model_dir, _ = realtime_model_dir
    with _client(monkeypatch, str(model_dir)) as client:
        body = client.get("/metadata").json()
        assert body["realtime_features"] == REALTIME_FEATURES
        assert body["realtime_history_minutes"] == 90


def test_realtime_client_need_not_send_rt_features(monkeypatch, realtime_model_dir):
    model_dir, booster = realtime_model_dir
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post("/predict", json={"items": [_rt_item("r0", with_identity=False)]})
        assert res.status_code == 200
        result = res.json()["results"][0]
        assert result["status"] == "AVAILABLE"
        # no identity, no history -> the model's NaN (no-history) path
        assert result["seconds_to_arrival"] == pytest.approx(
            _offline_prediction(booster, _rt_item("r0", with_identity=False), None), abs=1e-6)


def test_realtime_prediction_matches_offline_feature_pipeline(monkeypatch, realtime_model_dir):
    """Serving derives rt_* from route_history with the same function build_dataset.py
    used, so a served prediction equals the offline one for the same observation."""
    model_dir, booster = realtime_model_dir
    item = _rt_item("r0")
    history = _route_history()
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post("/predict", json={"items": [item], "route_history": {"143": history}})
        assert res.status_code == 200
        served = res.json()["results"][0]["seconds_to_arrival"]
        expected = _offline_prediction(booster, item, history)
        assert served == pytest.approx(expected, abs=1e-6)
        # the history actually changed the answer (rt features reached the booster)
        assert served != pytest.approx(_offline_prediction(booster, item, None), abs=1e-6)


def test_realtime_history_for_other_route_is_ignored(monkeypatch, realtime_model_dir):
    model_dir, booster = realtime_model_dir
    item = _rt_item("r0")
    with _client(monkeypatch, str(model_dir)) as client:
        res = client.post("/predict", json={"items": [item], "route_history": {"999": _route_history()}})
        assert res.json()["results"][0]["seconds_to_arrival"] == pytest.approx(
            _offline_prediction(booster, item, None), abs=1e-6)
