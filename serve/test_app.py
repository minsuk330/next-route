"""Tests for the serving app. Builds a tiny real LightGBM model so the
pandas_categorical contract and feature ordering are exercised end to end.

Run: uv run --extra serve --extra serve-dev pytest ml/serve/test_app.py
"""

from __future__ import annotations

import importlib
import json
from pathlib import Path

import lightgbm as lgb
import pandas as pd
import pytest
from fastapi.testclient import TestClient

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
                "schema_version": 1,
                "with_api_feature": False,
                "feature_list": FEATURES,
                "model_version": "test-model-v1",
            }
        )
    )


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
        assert res.json()["model_version"] == "test-model-v1"


def test_health_503_without_model(monkeypatch):
    with _client(monkeypatch, None) as client:
        res = client.get("/health")
        assert res.status_code == 503
        assert res.json()["status"] == "unavailable"


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
