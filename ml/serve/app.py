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
import polars as pl
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from feature_engineering import SERVABLE_ENCODING_SPECS, compute_serving_features, encoding_name

logger = logging.getLogger("ml.serve")

# Serving only accepts models trained against this dataset schema and without the
# leakage-aware api feature (see ml/train.py --with-api-feature). A mismatched
# artifact would break the fixed /predict feature contract, so we refuse to load it.
EXPECTED_SCHEMA_VERSION = 2
CATEGORICAL_FEATURE = "route_id"
FORBIDDEN_FEATURE = "api_target_seconds_to_arrival"

# Every raw field the Java MlFeatureVectorBuilder always sends per item, regardless
# of which model ends up serving it (mirrors train.py's NUMERIC_FEATURES; duplicated
# here — not imported — to keep serve decoupled from train.py's argparse/training-only
# deps). Needed for a target-encoding model A: its feature_list mixes plain
# pass-through numerics (e.g. temperature_c) with route-profile/te_* columns
# compute_serving_features derives from a subset of these same raw fields
# (current_section_order, target_seq, remaining_stop_count, congestion,
# current_full_section_distance, hour_of_day, minute_of_day, day_of_week) — so the
# raw frame needs the full set to cover both.
RAW_FEATURE_NAMES = [
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
]


class ModelState:
    """Holds the loaded booster(s) and their contract, or the reason it is unavailable.

    booster is model A (dense, route-keyed). booster_b, when router_enabled, is
    model B (route-agnostic) — the fallback for routes not in route_category_set.
    """

    def __init__(self) -> None:
        self.booster: lgb.Booster | None = None
        self.manifest: dict[str, Any] = {}
        self.feature_names: list[str] = []
        self.numeric_features: list[str] = []
        # model_categories: booster의 pandas_categorical(train∪test). DataFrame dtype 인코딩용 — 절대 부분집합으로 바꾸면 category code 깨짐.
        self.model_categories: list[str] = []
        # route_categories: 실제 학습된 route(manifest.training_route_categories). 없으면 model_categories로 폴백.
        # 지원 판정(metadata + predict 라우팅)의 권위 소스: seen -> model A, unseen -> model B(있으면).
        self.route_categories: list[str] = []
        self.route_category_set: set[str] = set()
        self.model_version: str | None = None
        self.error: str | None = None
        # router (model B, route-agnostic fallback for unseen routes) — optional.
        self.router_enabled: bool = False
        self.booster_b: lgb.Booster | None = None
        self.feature_names_b: list[str] = []
        # target encoding (model A route-keyed te_* features) — optional, requires
        # router_enabled (train.py's validate_target_encoding). When set, model A's
        # feature_list has no route_id categorical; these hold what
        # compute_serving_features needs to rebuild the te_*/derived columns from a
        # request's raw fields at predict time.
        self.target_encoding_enabled: bool = False
        self.route_profile: pl.DataFrame | None = None
        self.encoding_tables: dict[str, pl.DataFrame] = {}
        self.global_pace: float = 0.0
        self.te_smoothing: float = 0.0

    @property
    def loaded(self) -> bool:
        if self.booster is None:
            return False
        return not self.router_enabled or self.booster_b is not None


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

    # --- feature contract enforcement (model A) ----------------------------
    # A target-encoding model A has te_*/derived features instead of a raw route_id
    # categorical (Stage 0, 2026-08-15: a bare route_id categorical actively hurt
    # routing) — the contract and categorical-count checks flip accordingly.
    target_encoding_enabled = bool(manifest.get("target_encoding_enabled"))
    error = _validate_contract(
        manifest, feature_names, "feature_list", require_categorical=not target_encoding_enabled
    )
    if error is not None:
        state.error = error
        logger.error("[serve] contract rejected: %s", error)
        return

    pandas_categorical = booster.pandas_categorical or []
    expected_categoricals = 0 if target_encoding_enabled else 1
    if len(pandas_categorical) != expected_categoricals:
        state.error = (
            f"expected exactly {expected_categoricals} categorical column(s), "
            f"got {len(pandas_categorical)}"
        )
        logger.error("[serve] %s", state.error)
        return

    # --- model B (route-agnostic fallback), only if the manifest says so ---
    router_enabled = bool(manifest.get("router_enabled"))
    booster_b: lgb.Booster | None = None
    feature_names_b: list[str] = []
    if router_enabled:
        model_b_path = model_path.parent / "model_b.txt"
        if not model_b_path.exists():
            state.error = f"router_enabled but model_b not found: {model_b_path}"
            logger.error("[serve] %s", state.error)
            return
        try:
            booster_b = lgb.Booster(model_file=str(model_b_path))
        except Exception as exc:  # noqa: BLE001
            state.error = f"failed to load booster_b: {exc}"
            logger.error("[serve] %s", state.error)
            return
        feature_names_b = list(booster_b.feature_name())
        error = _validate_contract(
            manifest, feature_names_b, "feature_list_b", require_categorical=False
        )
        if error is not None:
            state.error = f"model_b: {error}"
            logger.error("[serve] contract rejected: %s", state.error)
            return
        if booster_b.pandas_categorical:
            state.error = "model_b must have no categorical columns"
            logger.error("[serve] %s", state.error)
            return

    # --- target encoding (model A route-keyed te_* features), only if manifest says so ---
    route_profile: pl.DataFrame | None = None
    encoding_tables: dict[str, pl.DataFrame] = {}
    global_pace = 0.0
    te_smoothing = 0.0
    if target_encoding_enabled:
        # persist_encoding_artifacts always writes under <experiment_dir>/encoding_tables/
        # (feature_engineering.py), but the manifest's feature_policy stores the
        # *training-time* absolute path to those files. That path goes stale the moment
        # the experiment directory is rsync'd elsewhere (ops/deploy_model.sh ships it to
        # the VPS at a different absolute path) — so resolve from model_path's current
        # location instead, mirroring how _resolve_paths already treats model.txt.
        feature_policy = manifest.get("feature_policy") or {}
        stats = feature_policy.get("encoding_serving_stats") or {}
        tables_dir = model_path.parent / "encoding_tables"
        try:
            route_profile = pl.read_parquet(tables_dir / "route_profile.parquet")
            encoding_tables = {
                encoding_name(keys, value): pl.read_parquet(
                    tables_dir / f"{encoding_name(keys, value)}.parquet"
                )
                for keys, value in SERVABLE_ENCODING_SPECS
            }
            global_pace = float(stats["global_pace"])
            te_smoothing = float(feature_policy["target_encoding"]["smoothing"])
        except Exception as exc:  # noqa: BLE001
            state.error = f"target_encoding_enabled but failed to load encoding artifacts: {exc}"
            logger.error("[serve] %s", state.error)
            return

    state.booster = booster
    state.manifest = manifest
    state.feature_names = feature_names
    state.numeric_features = [f for f in feature_names if f != CATEGORICAL_FEATURE]
    state.model_categories = [str(c) for c in pandas_categorical[0]] if pandas_categorical else []
    state.target_encoding_enabled = target_encoding_enabled
    state.route_profile = route_profile
    state.encoding_tables = encoding_tables
    state.global_pace = global_pace
    state.te_smoothing = te_smoothing
    # 지원 route = manifest.training_route_categories(실제 학습된 route). 구 모델(필드 없음)은
    # model_categories(train∪test)로 폴백 — 하위호환. training set은 train⊆train∪test라 항상 부분집합.
    trained = manifest.get("training_route_categories")
    if trained:
        state.route_categories = sorted(str(c) for c in trained)
    else:
        state.route_categories = state.model_categories
    state.route_category_set = set(state.route_categories)
    state.model_version = manifest.get("model_version") or model_path.parent.name
    state.router_enabled = router_enabled
    state.booster_b = booster_b
    state.feature_names_b = feature_names_b
    logger.info(
        "[serve] model loaded: version=%s features=%d routes=%d router_enabled=%s",
        state.model_version,
        len(feature_names),
        len(state.route_categories),
        state.router_enabled,
    )


def _validate_contract(
    manifest: dict[str, Any],
    feature_names: list[str],
    feature_list_key: str = "feature_list",
    require_categorical: bool = True,
) -> str | None:
    if require_categorical and CATEGORICAL_FEATURE not in feature_names:
        return f"'{CATEGORICAL_FEATURE}' missing from model features"
    if not require_categorical and CATEGORICAL_FEATURE in feature_names:
        return f"'{CATEGORICAL_FEATURE}' unexpectedly present in route-agnostic model features"
    if FORBIDDEN_FEATURE in feature_names:
        return f"model includes forbidden feature '{FORBIDDEN_FEATURE}'"
    if manifest:
        schema = manifest.get("schema_version")
        if schema != EXPECTED_SCHEMA_VERSION:
            return f"schema_version {schema} != expected {EXPECTED_SCHEMA_VERSION}"
        if manifest.get("with_api_feature") is True:
            return "model trained with_api_feature=true is not servable"
        feature_list = manifest.get(feature_list_key)
        if feature_list is not None and list(feature_list) != feature_names:
            return f"manifest {feature_list_key} does not match model feature order"
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
            # 백엔드가 "환승 예측 가능" 배지 판정에 사용. router_enabled=false면 predict 격리 기준과 동일;
            # true면 route_categories 밖 route도 model B로 예측되지만, "고정밀(A)" 커버리지 신호로는 유효.
            "route_categories": state.route_categories,
            # 디버그: booster categorical(train∪test) 크기. route_count보다 크면 test-only/미학습 route 존재.
            "model_route_count": len(state.model_categories),
            "router_enabled": state.router_enabled,
            "feature_list_b": state.feature_names_b if state.router_enabled else None,
            "target_encoding_enabled": state.target_encoding_enabled,
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
    # A target-encoding model A's feature_list has te_*/derived columns the client
    # never sends (compute_serving_features derives them here) — the raw contract to
    # require from the client is feature_names_b (plain numerics) plus route_id
    # (needed for routing and the encoding's route-keyed lookups either way).
    required_features = (
        set(state.feature_names_b) | {CATEGORICAL_FEATURE}
        if state.target_encoding_enabled
        else set(state.feature_names)
    )
    for item in items:
        missing = [f for f in required_features if f not in item.features]
        if missing:
            return JSONResponse(
                {"detail": f"request_id={item.request_id} missing features: {missing}"},
                status_code=422,
            )

    # --- per-item routing: seen route -> model A, unseen -> model B (if any) ---
    # Without a router, an unseen route was terminal (UNSUPPORTED_ROUTE, never
    # predicted). With one, it's routed to model B instead — same idea as
    # train_advanced.py's --router-model-a, applied to the servable feature set.
    results: dict[str, dict[str, Any]] = {}
    predictable_a: list[PredictItem] = []
    predictable_b: list[PredictItem] = []
    for item in items:
        route = item.features.get(CATEGORICAL_FEATURE)
        route_str = None if route is None else str(route)
        if route_str is not None and route_str in state.route_category_set:
            predictable_a.append(item)
        elif state.router_enabled:
            predictable_b.append(item)
        else:
            results[item.request_id] = {
                "request_id": item.request_id,
                "status": "UNSUPPORTED_ROUTE",
                "seconds_to_arrival": None,
                "model_version": state.model_version,
            }

    if predictable_a:
        frame = (
            _build_frame_target_encoded(predictable_a, state)
            if state.target_encoding_enabled
            else _build_frame(predictable_a, state.feature_names, state.model_categories)
        )
        preds = state.booster.predict(frame)
        for item, pred in zip(predictable_a, preds):
            results[item.request_id] = {
                "request_id": item.request_id,
                "status": "AVAILABLE",
                "seconds_to_arrival": float(pred),
                "model_version": state.model_version,
                "model": "a",
            }

    if predictable_b:
        frame_b = _build_frame(predictable_b, state.feature_names_b, None)
        preds_b = state.booster_b.predict(frame_b)
        for item, pred in zip(predictable_b, preds_b):
            results[item.request_id] = {
                "request_id": item.request_id,
                "status": "AVAILABLE",
                "seconds_to_arrival": float(pred),
                "model_version": state.model_version,
                "model": "b",
            }

    return JSONResponse({"results": [results[rid] for rid in request_ids]})


def _build_frame(
    items: list[PredictItem], feature_names: list[str], categories: list[str] | None
) -> pd.DataFrame:
    """Build a feature-ordered DataFrame for one booster. ``categories`` is the
    trained categorical dtype for route_id (model A); None means the feature set
    has no categorical column (model B, route-agnostic)."""
    columns: dict[str, list[Any]] = {f: [] for f in feature_names}
    for item in items:
        for f in feature_names:
            if f == CATEGORICAL_FEATURE:
                continue
            value = item.features.get(f)
            columns[f].append(math.nan if value is None else float(value))
        if CATEGORICAL_FEATURE in feature_names:
            route = item.features.get(CATEGORICAL_FEATURE)
            columns[CATEGORICAL_FEATURE].append(str(route))

    frame = pd.DataFrame(columns)
    if categories is not None and CATEGORICAL_FEATURE in feature_names:
        # dtype은 booster의 full categorical(model_categories)이어야 category code가 일치한다.
        # 지원 판정은 route_categories로 이미 격리됐고, predictable route ⊆ training ⊆ model_categories.
        frame[CATEGORICAL_FEATURE] = pd.Categorical(
            frame[CATEGORICAL_FEATURE], categories=categories
        )
    return frame[feature_names]


def _build_frame_target_encoded(items: list[PredictItem], state: ModelState) -> pd.DataFrame:
    """Model A frame when target_encoding_enabled: pull every raw field the client
    sends, derive the route-profile/te_* columns via feature_engineering's shared
    formula (the same one training used for test rows), then select the model's
    exact feature_list — dropping route_id, which model A never trains on here."""
    columns: dict[str, list[Any]] = {f: [] for f in RAW_FEATURE_NAMES}
    routes: list[str] = []
    for item in items:
        for f in RAW_FEATURE_NAMES:
            value = item.features.get(f)
            columns[f].append(math.nan if value is None else float(value))
        routes.append(str(item.features.get(CATEGORICAL_FEATURE)))

    raw = pl.DataFrame(columns).with_columns(pl.Series(CATEGORICAL_FEATURE, routes))
    computed = compute_serving_features(
        raw, state.route_profile, state.encoding_tables, state.global_pace, state.te_smoothing
    )
    return computed.select(state.feature_names).to_pandas()
