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


FEATURES_B = [f for f in FEATURES if f != "route_id"]


def _build_router_model(model_dir: Path, training_routes: list[str]) -> None:
    """Like _build_model, but also trains model_b.txt (route-agnostic) and marks
    the manifest router_enabled — the dual-model fixture for router tests."""
    _build_model(model_dir)
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
                "y": 300 + (i % 600),  # different y range so A/B predictions are distinguishable
            }
        )
    frame = pd.DataFrame(rows)
    x = frame[FEATURES_B]
    y = frame["y"]
    booster_b = lgb.train(
        {"objective": "mae", "num_leaves": 7, "verbose": -1},
        lgb.Dataset(x, y),
        num_boost_round=5,
    )
    booster_b.save_model(str(model_dir / "model_b.txt"))
    manifest_path = model_dir / "training_manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["router_enabled"] = True
    manifest["feature_list_b"] = FEATURES_B
    manifest["training_route_categories"] = training_routes
    manifest_path.write_text(json.dumps(manifest))


@pytest.fixture()
def router_model_dir(tmp_path) -> Path:
    d = tmp_path / "router_experiment"
    _build_router_model(d, training_routes=["143", "272"])
    return d


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


# --- router (model A + model B fallback) -----------------------------------


def test_metadata_reports_router_enabled(monkeypatch, router_model_dir):
    with _client(monkeypatch, str(router_model_dir)) as client:
        body = client.get("/metadata").json()
        assert body["router_enabled"] is True
        assert body["feature_list_b"] == FEATURES_B


def test_predict_seen_route_uses_model_a(monkeypatch, router_model_dir):
    with _client(monkeypatch, str(router_model_dir)) as client:
        res = client.post("/predict", json={"items": [_item("r1", route="143")]})
        assert res.status_code == 200
        result = res.json()["results"][0]
        assert result["status"] == "AVAILABLE"
        assert result["model"] == "a"


def test_predict_unseen_route_falls_back_to_model_b(monkeypatch, router_model_dir):
    # 160 is a valid category on the booster but not in training_route_categories
    # (["143", "272"]) — without a router this would be UNSUPPORTED_ROUTE.
    with _client(monkeypatch, str(router_model_dir)) as client:
        res = client.post("/predict", json={"items": [_item("r1", route="160")]})
        assert res.status_code == 200
        result = res.json()["results"][0]
        assert result["status"] == "AVAILABLE"
        assert isinstance(result["seconds_to_arrival"], float)
        assert result["model"] == "b"


def test_predict_unknown_route_also_falls_back_to_model_b(monkeypatch, router_model_dir):
    # a route id never seen by either booster still gets a model B prediction —
    # B never needed route_id as a feature, so it isn't limited to known categories.
    with _client(monkeypatch, str(router_model_dir)) as client:
        res = client.post("/predict", json={"items": [_item("r1", route="999999")]})
        assert res.status_code == 200
        result = res.json()["results"][0]
        assert result["status"] == "AVAILABLE"
        assert result["model"] == "b"


def test_router_load_fails_if_model_b_missing(monkeypatch, router_model_dir):
    (router_model_dir / "model_b.txt").unlink()
    with _client(monkeypatch, str(router_model_dir)) as client:
        res = client.get("/health")
        assert res.status_code == 503
        assert "model_b" in res.json()["error"]


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
    """Trains a real target-encoding model A (+ plain model B) the same way
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
        numeric_features=FEATURES_B,
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

    x_train_b = train_pl.select(FEATURES_B).to_pandas()
    y_train_b = train_pl["target_seconds"].to_pandas()
    booster_b = lgb.train(
        {"objective": "mae", "num_leaves": 7, "verbose": -1},
        lgb.Dataset(x_train_b, y_train_b),
        num_boost_round=5,
    )
    booster_b.save_model(str(model_dir / "model_b.txt"))

    safe_policy = persist_encoding_artifacts(model_dir, feature_policy)
    training_route_categories = sorted(set(train_pl["route_id"].to_list()))

    manifest = {
        "schema_version": 2,
        "with_api_feature": False,
        "feature_list": model_a_columns,
        "feature_list_b": FEATURES_B,
        "model_version": "test-te-model-v1",
        "router_enabled": True,
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
        assert all(r["status"] == "AVAILABLE" and r["model"] == "a" for r in results)
        served = [r["seconds_to_arrival"] for r in results]
        for served_value, offline_value in zip(served, predictions):
            assert served_value == pytest.approx(offline_value, abs=1e-4)


def test_target_encoding_unseen_route_falls_back_to_model_b(
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
        assert result["status"] == "AVAILABLE"
        assert result["model"] == "b"


def test_target_encoding_missing_raw_feature_422(monkeypatch, target_encoding_model_dir):
    model_dir, rows, _predictions = target_encoding_model_dir
    with _client(monkeypatch, str(model_dir)) as client:
        item = {k: v for k, v in rows[0].items() if k != "target_seconds"}
        del item["congestion"]
        res = client.post(
            "/predict", json={"items": [{"request_id": "r0", "features": item}]}
        )
        assert res.status_code == 422


def test_non_router_model_unaffected(monkeypatch, model_dir):
    # a plain single-model experiment dir (no model_b.txt, no router_enabled key)
    # must behave exactly as it did before this feature existed — rollback safety.
    with _client(monkeypatch, str(model_dir)) as client:
        assert client.get("/metadata").json()["router_enabled"] is False
        res = client.post(
            "/predict",
            json={"items": [_item("r1", route="143"), _item("bad", route="99999")]},
        )
        by_id = {r["request_id"]: r for r in res.json()["results"]}
        assert by_id["r1"]["status"] == "AVAILABLE"
        assert by_id["bad"]["status"] == "UNSUPPORTED_ROUTE"
