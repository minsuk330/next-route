# PR1 — Python FastAPI ML Serving

> 환승 지점 도착예측 통합 — ML 모델 serving 레이어 구축.
> 상위 맥락: [README](./README.md) · 후속: [PR2](./PR2-java-ml-client.md) · [PR3](./PR3-transfer-arrival-enricher.md) · [PR4](./PR4-ops-hardening.md)

## Context

ML 레이어는 batch 파이프라인뿐(archive→dataset→train LightGBM `model.txt`). serving HTTP 엔드포인트가
없어 Java가 실시간으로 도착예측을 받을 수 없다. 이 PR은 `model.txt`를 로드해 예측을 내려주는 FastAPI 서버를
신규로 만든다. batch 스크립트(`archive.py` 등)와 분리된 상시 기동 서비스다.

## 작업

### `ml/serve/app.py` (신규, FastAPI)
- 기동 시 `ML_MODEL_PATH`(experiment dir 또는 model.txt)에서 `lightgbm.Booster` 로드 + 같은 dir
  `training_manifest.json` 로드. `feature_list`/`schema_version` 검증.
- **feature 계약 강제**(잘못된 artifact 선택 방지): `train.py --with-api-feature`는 **동일 `schema_version=1`**로
  `api_target_seconds_to_arrival`을 feature에 추가하므로 schema_version만으로 구분 불가. 고정 `/predict` 계약에는
  이 feature가 없어 잘못된 experiment 로드 시 기동 실패/지속 422 발생. → 기동 시 **manifest `with_api_feature==false`
  강제 + `feature_list`가 예상 목록과 정확히 일치** 검증(불일치면 기동 거부). 또는 `/metadata`로 schema/feature
  계약을 노출해 클라이언트가 사전 검증.
- **categorical 복원**: `Booster.pandas_categorical`에서 `route_id` category 순서 복원. 예측 시 입력을
  동일 `pandas.CategoricalDtype(categories=...)`로 변환. (`train.py`가 학습 시 pandas CategoricalDtype를
  `model.txt`의 `pandas_categorical`로 저장하므로 그 순서를 그대로 맞춰야 재현됨.)
- `POST /predict`: body = `{request_id, <feature 키들>}` 리스트. key = feature 이름, `train.py`
  NUMERIC+CATEGORICAL과 동일. `model.feature_name()` 순서로 정렬해 예측.
  - **응답은 ID 결합 계약**: `[{request_id, status, seconds_to_arrival, model_version}]`. 순서 의존 금지.
- **부분 실패 격리** (한 잘못된 route가 batch 전체를 깨면 안 됨):
  | 상황 | 처리 |
  |---|---|
  | feature **key 누락** | HTTP 422 (요청 자체 거부) |
  | 존재하는 key의 **null 값** | 허용 — Pandas NaN으로 변환(학습에서 GPS·congestion·section_progress 등 null 가능) |
  | 미등록 `route_id` | 그 item만 `status=UNSUPPORTED_ROUTE` (seconds 없음), 나머지 정상 item 계속 예측 |
  | 정상 item | `status=AVAILABLE` |
  | 전체 | HTTP 200 + item별 status |

  > nullable 계약: **누락 key=422, key 존재 시 null 값은 NaN 허용**. 이 둘을 구분.
- `GET /health`: 모델·manifest 로드 여부 (없으면 503). serving 자체는 모델 없어도 기동.

### `ml/pyproject.toml`
- `serve` extra 추가: `["fastapi", "uvicorn", "lightgbm", "pandas"]`.

### `ml/Dockerfile`
- 현재 `uv sync --frozen --no-dev --no-install-project` → **`--extra serve` 추가**.
- `uv.lock`도 serve extra 포함되게 갱신. batch CMD는 compose에서 override.

### compose
- 기존 batch `nextroute-ml`(run --rm) 유지.
- **신규 long-running `nextroute-ml-serve`**: 동일 이미지,
  `command: uvicorn serve.app:app --host 0.0.0.0 --port 8001`.
  - **모델 mount(읽기전용) + `ML_MODEL_PATH` 명시** — mount·env 없으면 모델 로드 실패로 MODEL 분기가 항상 unavailable.
  - **`GET /health` 기반 healthcheck**.
- **app 컨테이너 연결**: Java 기본 `ML_PREDICTOR_URL=http://localhost:8001`은 **app 자기 자신**을 가리킴 →
  compose에서 **`ML_PREDICTOR_URL=http://nextroute-ml-serve:8001`(서비스명)** 주입.
  - ⚠️ **app은 serve `service_healthy`에 hard-depend 금지** — 모델 mount 누락/manifest 오류로 `/health` 503이면
    ML뿐 아니라 **경로검색 앱 전체가 기동 실패**. ML은 선택 기능이므로 app은 **serve와 독립 기동**, 호출 실패는
    timeout+fallback(`MODEL_UNAVAILABLE`)으로 흡수. (`depends_on`은 조건 없이 두거나 생략.)
- GHCR 빌드는 기존 `.github/workflows/ml-image.yml` 재사용.

## Feature 이름 (train.py / build_dataset.py와 정확히 동일)
```
NUMERIC: current_section_order, section_progress, current_section_distance,
         current_full_section_distance, next_stop_time, last_stop_time, congestion,
         gps_x, gps_y, target_seq, remaining_stop_count, hour_of_day, minute_of_day,
         day_of_week(1=월~7=일), is_weekend
CATEGORICAL: route_id
```

## Verification
- 더미 `model.txt` + manifest 생성 → `/predict` 단위테스트: 정상 / 미등록 route 422가 아닌 item status / 누락 feature 422.
- `/health` 503(모델 없음) 확인.
- **모델 없이 app 기동**: serve `/health` 503이어도 경로검색 app은 정상 기동(serve 의존성으로 app 기동 실패 안 함) 검증.
- `curl` 스모크.
