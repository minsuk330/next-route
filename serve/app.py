"""FastAPI serving for the bus arrival LightGBM model.

Loads a trained ``model.txt`` (LightGBM Booster) plus its ``training_manifest.json``
and exposes batch prediction over HTTP. The model is optional: if no model is
configured or it fails the contract checks the process still boots and ``/health``
returns 503 so the Java app can degrade gracefully (MODEL_UNAVAILABLE).

Request/response use an explicit ``request_id`` join contract — never rely on order.
"""

from __future__ import annotations

import logging
import math
import os
from pathlib import Path
from typing import Any

from contextlib import asynccontextmanager

import lightgbm as lgb
import pandas as pd
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

logger = logging.getLogger("ml.serve")

# Serving only accepts models trained against this dataset schema and without the
# leakage-aware api feature (see ml/train.py --with-api-feature). A mismatched
# artifact would break the fixed /predict feature contract, so we refuse to load it.
EXPECTED_SCHEMA_VERSION = 1
CATEGORICAL_FEATURE = "route_id"
FORBIDDEN_FEATURE = "api_target_seconds_to_arrival"


class ModelState:
    """Holds the loaded booster and its contract, or the reason it is unavailable."""

    def __init__(self) -> None:
        self.booster: lgb.Booster | None = None
        self.manifest: dict[str, Any] = {}
        self.feature_names: list[str] = []
        self.numeric_features: list[str] = []
        # model_categories: booster의 pandas_categorical(train∪test). DataFrame dtype 인코딩용 — 절대 부분집합으로 바꾸면 category code 깨짐.
        self.model_categories: list[str] = []
        # route_categories: 실제 학습된 route(manifest.training_route_categories). 없으면 model_categories로 폴백.
        # 지원 판정(metadata + predict 격리)의 권위 소스.
        self.route_categories: list[str] = []
        self.route_category_set: set[str] = set()
        self.model_version: str | None = None
        self.error: str | None = None

    @property
    def loaded(self) -> bool:
        return self.booster is not None


state = ModelState()


def _resolve_paths(raw: str) -> tuple[Path, Path]:
    """Resolve ML_MODEL_PATH (experiment dir or model.txt) to (model, manifest)."""
    path = Path(raw).expanduser()
    if path.is_dir():
        return path / "model.txt", path / "training_manifest.json"
    return path, path.parent / "training_manifest.json"


def load_model(state: ModelState) -> None:
    """Populate ``state`` from ML_MODEL_PATH. On any failure leave it unloaded."""
    state.__init__()  # reset

    raw = os.getenv("ML_MODEL_PATH")
    if not raw:
        state.error = "ML_MODEL_PATH not set"
        logger.warning("[serve] %s — booting without model", state.error)
        return

    model_path, manifest_path = _resolve_paths(raw)
    if not model_path.exists():
        state.error = f"model file not found: {model_path}"
        logger.warning("[serve] %s — booting without model", state.error)
        return

    try:
        booster = lgb.Booster(model_file=str(model_path))
    except Exception as exc:  # noqa: BLE001 - report any load failure as unavailable
        state.error = f"failed to load booster: {exc}"
        logger.error("[serve] %s", state.error)
        return

    feature_names = list(booster.feature_name())

    manifest: dict[str, Any] = {}
    if manifest_path.exists():
        import json

        try:
            manifest = json.loads(manifest_path.read_text())
        except Exception as exc:  # noqa: BLE001
            state.error = f"failed to read manifest: {exc}"
            logger.error("[serve] %s", state.error)
            return

    # --- feature contract enforcement -------------------------------------
    error = _validate_contract(manifest, feature_names)
    if error is not None:
        state.error = error
        logger.error("[serve] contract rejected: %s", error)
        return

    pandas_categorical = booster.pandas_categorical or []
    if len(pandas_categorical) != 1:
        state.error = (
            f"expected exactly 1 categorical column, got {len(pandas_categorical)}"
        )
        logger.error("[serve] %s", state.error)
        return

    state.booster = booster
    state.manifest = manifest
    state.feature_names = feature_names
    state.numeric_features = [f for f in feature_names if f != CATEGORICAL_FEATURE]
    state.model_categories = [str(c) for c in pandas_categorical[0]]
    # 지원 route = manifest.training_route_categories(실제 학습된 route). 구 모델(필드 없음)은
    # model_categories(train∪test)로 폴백 — 하위호환. training set은 train⊆train∪test라 항상 부분집합.
    trained = manifest.get("training_route_categories")
    if trained:
        state.route_categories = sorted(str(c) for c in trained)
    else:
        state.route_categories = state.model_categories
    state.route_category_set = set(state.route_categories)
    state.model_version = manifest.get("model_version") or model_path.parent.name
    logger.info(
        "[serve] model loaded: version=%s features=%d routes=%d",
        state.model_version,
        len(feature_names),
        len(state.route_categories),
    )


def _validate_contract(manifest: dict[str, Any], feature_names: list[str]) -> str | None:
    if CATEGORICAL_FEATURE not in feature_names:
        return f"'{CATEGORICAL_FEATURE}' missing from model features"
    if FORBIDDEN_FEATURE in feature_names:
        return f"model includes forbidden feature '{FORBIDDEN_FEATURE}'"
    if manifest:
        schema = manifest.get("schema_version")
        if schema != EXPECTED_SCHEMA_VERSION:
            return f"schema_version {schema} != expected {EXPECTED_SCHEMA_VERSION}"
        if manifest.get("with_api_feature") is True:
            return "model trained with_api_feature=true is not servable"
        feature_list = manifest.get("feature_list")
        if feature_list is not None and list(feature_list) != feature_names:
            return "manifest feature_list does not match model feature order"
    return None


# --- request/response models ----------------------------------------------


class PredictItem(BaseModel):
    request_id: str = Field(..., min_length=1)
    # Values are kept untyped: route_id is categorical (string) while the rest are
    # numeric. Typing as float would coerce route_id and break category lookup.
    features: dict[str, Any]


class PredictRequest(BaseModel):
    items: list[PredictItem]


@asynccontextmanager
async def lifespan(app: FastAPI):
    logging.basicConfig(level=logging.INFO)
    load_model(state)
    yield


app = FastAPI(title="nextroute-ml serving", lifespan=lifespan)


@app.get("/health")
def health() -> JSONResponse:
    if state.loaded:
        return JSONResponse(
            {
                "status": "ok",
                "model_version": state.model_version,
                "feature_count": len(state.feature_names),
            }
        )
    return JSONResponse(
        {"status": "unavailable", "error": state.error}, status_code=503
    )


@app.get("/metadata")
def metadata() -> JSONResponse:
    if not state.loaded:
        return JSONResponse(
            {"status": "unavailable", "error": state.error}, status_code=503
        )
    return JSONResponse(
        {
            "model_version": state.model_version,
            "schema_version": EXPECTED_SCHEMA_VERSION,
            "feature_list": state.feature_names,
            "categorical_feature": CATEGORICAL_FEATURE,
            "route_count": len(state.route_categories),
            # 실제 학습된 지원 route_id 목록(manifest.training_route_categories, 폴백 시 model categorical).
            # 백엔드가 "환승 예측 가능" 배지 판정에 사용. predict 격리 기준과 동일.
            "route_categories": state.route_categories,
            # 디버그: booster categorical(train∪test) 크기. route_count보다 크면 test-only/미학습 route 존재.
            "model_route_count": len(state.model_categories),
        }
    )


@app.post("/predict")
def predict(request: PredictRequest) -> JSONResponse:
    if not state.loaded:
        return JSONResponse(
            {"status": "unavailable", "error": state.error}, status_code=503
        )

    items = request.items

    # --- envelope invariants: reject the whole request (422) --------------
    request_ids = [item.request_id for item in items]
    if len(request_ids) != len(set(request_ids)):
        return JSONResponse(
            {"detail": "duplicate request_id in batch"}, status_code=422
        )
    for item in items:
        missing = [f for f in state.feature_names if f not in item.features]
        if missing:
            return JSONResponse(
                {"detail": f"request_id={item.request_id} missing features: {missing}"},
                status_code=422,
            )

    # --- per-item status: unsupported route isolated, rest predicted ------
    results: dict[str, dict[str, Any]] = {}
    predictable: list[PredictItem] = []
    for item in items:
        route = item.features.get(CATEGORICAL_FEATURE)
        route_str = None if route is None else str(route)
        if route_str is None or route_str not in state.route_category_set:
            results[item.request_id] = {
                "request_id": item.request_id,
                "status": "UNSUPPORTED_ROUTE",
                "seconds_to_arrival": None,
                "model_version": state.model_version,
            }
        else:
            predictable.append(item)

    if predictable:
        frame = _build_frame(predictable)
        preds = state.booster.predict(frame)
        for item, pred in zip(predictable, preds):
            results[item.request_id] = {
                "request_id": item.request_id,
                "status": "AVAILABLE",
                "seconds_to_arrival": float(pred),
                "model_version": state.model_version,
            }

    return JSONResponse({"results": [results[rid] for rid in request_ids]})


def _build_frame(items: list[PredictItem]) -> pd.DataFrame:
    """Build a feature-ordered DataFrame; route_id as the trained Categorical."""
    columns: dict[str, list[Any]] = {f: [] for f in state.feature_names}
    for item in items:
        for f in state.numeric_features:
            value = item.features.get(f)
            columns[f].append(math.nan if value is None else float(value))
        route = item.features.get(CATEGORICAL_FEATURE)
        columns[CATEGORICAL_FEATURE].append(str(route))

    frame = pd.DataFrame(columns)
    # dtype은 booster의 full categorical(model_categories)이어야 category code가 일치한다.
    # 지원 판정은 route_categories로 이미 격리됐고, predictable route ⊆ training ⊆ model_categories.
    frame[CATEGORICAL_FEATURE] = pd.Categorical(
        frame[CATEGORICAL_FEATURE], categories=state.model_categories
    )
    return frame[state.feature_names]
