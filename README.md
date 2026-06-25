# NextRoute ML

버스 ML dataset 파이프라인은 Spring 애플리케이션과 분리해서 Python에서 실행한다.

## 역할

| Layer | Role | Storage |
|---|---|---|
| Spring | raw 수집, `bus_arrival_label_event` 생성 | Postgres |
| Python archive | label/position/candidate 원천 보존 | `data/bus_label`, `data/bus_position`, `data/bus_candidate` |
| Python dataset | 학습용 X/y cache 생성 | `data/dataset` |
| Python training | baseline/model 학습과 평가 | `models`, `reports` |

archive parquet가 ML 장기 진실원이고, dataset parquet는 재생성 가능한 cache다.

상위 문서:

- `../docs/ml-bus/bus-arrival-ml-dataset-todo.md`
- `../docs/ml-bus/bus-ml-dataset-pipeline.md`

## Setup

VPS Postgres로 SSH tunnel을 연다.

```bash
ssh -L 5433:localhost:5432 vps
```

환경 파일을 만든다.

```bash
cd ml
cp .env.example .env
```

`.env`를 실제 DB 값으로 수정한다.

```text
DB_URL=postgresql://USER:PASSWORD@localhost:5433/DBNAME
DATA_DIR=./data
```

로컬에서 dataset 생성과 학습까지 실행하려면 학습 extra까지 설치한다.

```bash
uv sync --extra train
```

또는:

```bash
python -m venv .venv
source .venv/bin/activate
python -m pip install -e .
```

## Archive

서비스일 단위로 label/position/candidate 원천 parquet을 생성한다.

```bash
uv run python archive.py 2026-06-10
```

이미 `data.parquet`가 있으면 기본적으로 skip한다.

```bash
uv run python archive.py 2026-06-10
```

재생성하려면 `--overwrite`를 사용한다.

```bash
uv run python archive.py 2026-06-10 --overwrite
```

밀린 날짜는 범위로 backfill한다.

```bash
uv run python archive.py --from 2026-06-10 --to 2026-06-12
```

출력:

```text
data/bus_label/service_date=2026-06-10/data.parquet
data/bus_label/service_date=2026-06-10/manifest.json

data/bus_position/service_date=2026-06-10/data.parquet
data/bus_position/service_date=2026-06-10/manifest.json

data/bus_candidate/service_date=2026-06-10/data.parquet
data/bus_candidate/service_date=2026-06-10/manifest.json
```

`archive.py`는 DB count와 parquet row count를 비교한다. 불일치하면 생성 파일을 삭제하고 비정상 종료한다.

## Sanity Check

```python
import polars as pl

labels = pl.scan_parquet("data/bus_label/**/*.parquet")
print(labels.select(pl.len()).collect())
```

## Build Dataset

archive parquet에서 학습용 dataset cache를 생성한다.

```bash
uv run python build_dataset.py 2026-06-10
```

이미 part 파일이 있으면 skip한다.

```bash
uv run python build_dataset.py 2026-06-10 --overwrite
```

여러 날짜를 생성할 때는 날짜 루프 옵션을 쓴다.

```bash
uv run python build_dataset.py --from 2026-06-10 --to 2026-06-12
```

출력:

```text
data/dataset/service_date=2026-06-10/part-0000.parquet
data/dataset/service_date=2026-06-10/manifest.json
```

`build_dataset.py`는 route 단위로 조인해 part 파일을 즉시 쓴다. dataset parquet는 archive에서 언제든 재생성 가능한 cache다.

시간 파생 컬럼:

```text
day_of_week = 1(월) ~ 7(일)
is_weekend = day_of_week in (6, 7)
```

## Validate

archive와 dataset 품질을 검증한다.

```bash
uv run python validate.py 2026-06-10
```

dataset cache가 아직 없으면 archive-only validation으로 통과한다. dataset cache가 있으면 archive와 dataset을 함께 검증한다.

출력:

```text
data/dataset/service_date=2026-06-10/validation.json
```

여러 날짜를 검증할 때는:

```bash
uv run python validate.py --from 2026-06-10 --to 2026-06-12
```

검증 실패 시 exit code 1을 반환한다.

## Baseline

비ML baseline을 평가한다. `--target`은 `api`, `label`, `corrected` 중 하나다.

```bash
uv run python baseline.py 2026-06-10 --target label
uv run python baseline.py 2026-06-10 --target api
uv run python baseline.py 2026-06-10 --target corrected
```

출력:

```text
data/experiments/{run_id}/baseline_report.json
```

baseline 종류:

- API: `api_target_seconds_to_arrival` 그대로 예측값으로 사용
- Median: train split의 `(route_id, current_section_order, target_seq, hour_of_day, day_of_week)` median lookup

## Train

학습 명령은 `train` extra 의존성이 필요하다.

```bash
uv sync --extra train
```

LightGBM 회귀 모델을 학습한다.

```bash
uv run python train.py 2026-06-10 --target label --sample-rows 2000000
```

target 실험:

```bash
uv run python train.py 2026-06-10 --target api
uv run python train.py 2026-06-10 --target label
uv run python train.py 2026-06-10 --target corrected
```

기본 split은 단일 service_date에서 `snapshot_at >= 21:00`을 test로 둔다. 여러 날짜 dataset이 쌓이면 service_date를 쉼표로 넘기고 `--test-dates`로 날짜 기준 split을 사용할 수 있다.

```bash
uv run python train.py 2026-06-10 --target label --test-from 21:00
uv run python train.py 2026-06-10,2026-06-11,2026-06-12 --target label --test-dates 2026-06-12
```

rolling window 학습에서는 날짜별 샘플을 먼저 만든 뒤 concat한다. 전체 dataset을 한 번에 collect하지 않는다.

```bash
uv run python train.py 2026-06-10,2026-06-11,2026-06-12 \
  --target label \
  --test-dates 2026-06-12 \
  --per-date-sample-rows 100000
```

기본 feature에서는 arrival 시각 3종과 target 3종을 제외한다. API target을 feature로 넣는 별도 실험은 명시적으로 실행한다.

```bash
uv run python train.py 2026-06-10 --target label --with-api-feature
```

출력:

```text
data/experiments/{run_id}/model.txt
data/experiments/{run_id}/metrics.json
data/experiments/{run_id}/training_manifest.json
```

첫 실험 성공 기준:

- median baseline 대비 MAE 개선
- 20~40분 horizon에서 의미 있는 개선
- 특정 route에만 과적합되지 않음
- target 정책별 차이를 설명 가능

## Serving

학습한 `model.txt`를 HTTP로 예측 제공하는 FastAPI 서버다. batch 스크립트(`archive.py` 등)와 분리된 상시 기동
서비스이며, Spring app이 환승 도착예측에 사용한다. 모델은 선택이다 — 모델이 없거나 계약 검증에 실패하면 프로세스는
기동하되 `/health`가 503을 반환해 app이 graceful하게 degrade한다.

serving 의존성:

```bash
uv sync --extra serve
```

`ML_MODEL_PATH`로 experiment 디렉터리(또는 `model.txt`)를 지정해 기동한다. 같은 디렉터리의
`training_manifest.json`을 함께 읽어 `schema_version=1`, `with_api_feature=false`, `feature_list==모델 feature`를
검증한다(불일치 시 로드 거부 → 503). `route_id`는 `model.txt`의 `pandas_categorical` 순서로 복원하고, 미등록
route는 item별 `UNSUPPORTED_ROUTE`로 격리한다.

```bash
ML_MODEL_PATH=data/experiments/lgbm_2026-06-10_label_20260612T080252Z \
  uv run --extra serve uvicorn serve.app:app --host 0.0.0.0 --port 8001
```

엔드포인트:

- `GET /health` — 모델 로드 여부. 없으면 503.
- `GET /metadata` — `model_version`, `feature_list`, `route_count` 등 계약.
- `POST /predict` — `{"items":[{"request_id","features":{...}}]}`. feature 키는 학습과 동일.
  - 응답은 **`request_id`로 결합**: `{"results":[{"request_id","status","seconds_to_arrival","model_version"}]}`.
  - feature **키 누락**은 HTTP 422(요청 거부), 존재하는 키의 **null 값은 허용**(NaN), **미등록 route_id**는
    그 item만 `UNSUPPORTED_ROUTE`(나머지는 계속 예측).

테스트:

```bash
uv run --extra serve --extra serve-dev pytest serve/test_app.py
```

배포: compose `nextroute-ml-serve` 서비스가 batch `nextroute-ml`과 동일 이미지로 상시 기동한다. `/data`(읽기전용
bind mount) 아래 experiment를 `ML_MODEL_PATH`로 가리키고, app은 `ML_PREDICTOR_URL=http://nextroute-ml-serve:8001`로
호출한다. app은 serve health에 의존하지 않아 모델이 없어도 정상 기동한다.

## Retention

기본은 dry-run이다. 삭제 대상 날짜와 archive gate 결과만 출력한다.

```bash
uv run python retention.py --dry-run
```

raw DB 3종은 기본 21일 rolling 보존이다.

```text
bus_position_raw: collected_at 기준
bus_arrival_candidate_raw: finalized_at 기준
bus_arrival_label_event: service_date 기준
```

삭제 대상 service_date라도 `bus_label`, `bus_position`, `bus_candidate` archive manifest와 parquet row count가 모두 맞는 날짜만 삭제 대상이 된다. archive가 없거나 검증이 실패한 날짜는 보존된다.

실삭제는 backup 재결정 후에만 실행한다.

```bash
uv run python retention.py --apply --confirm-backup-reviewed
```

dataset cache는 재생성 가능하므로 기본 최근 7일만 보존한다.

## VPS Operations

VPS에서는 `postgres`, `redis`, `app`, `nextroute-ml` compose 서비스가 같이 배포된다. `nextroute-ml` 서비스는 상시 기동하지 않고 host crontab이 `docker compose run --rm nextroute-ml ...`로 일회성 실행한다.

`nextroute-ml` 이미지는 VPS에서 직접 빌드하지 않는다. `ml/**`가 main에 push되면 `.github/workflows/ml-image.yml`이 `ghcr.io/<owner>/nextroute-ml:latest`(+`:sha`)를 빌드해 GHCR에 push하고, VPS는 `docker compose pull nextroute-ml`로 받는다. 코드 변경 시 VPS에서 수동 빌드/동기화가 필요 없다.

GHCR 패키지는 private이므로 VPS에서 1회 로그인한다. `read:packages` 권한 PAT가 필요하다.

```bash
echo "$GHCR_PAT" | docker login ghcr.io -u <github-username> --password-stdin
```

`nextroute-ml` 컨테이너는 `DB_URL`이 없어도 `env/db.env`의 `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`로 접속 URL을 조립한다. compose에서는 `POSTGRES_HOST=postgres`, `DATA_DIR=/data`를 주입하고 `/srv/nextroute/ml-data`를 `/data`로 bind mount한다.

archive 진실원은 VPS bind mount에 저장한다.

```text
/srv/nextroute/ml-data
```

첫 가동 전 로컬 archive seed가 필요하면 VPS로 복사한다.

```bash
rsync -av ml/data/ vps:/srv/nextroute/ml-data/
```

VPS host crontab 예시:

```cron
CRON_TZ=Asia/Seoul
10 5 * * * cd /path/to/nextroute && docker compose pull nextroute-ml >> /var/log/nextroute-ml.log 2>&1
20 5 * * * cd /path/to/nextroute && docker compose run --rm nextroute-ml python archive.py $(date -d yesterday +\%F) >> /var/log/nextroute-ml.log 2>&1
40 5 * * * cd /path/to/nextroute && { docker compose run --rm nextroute-ml python validate.py $(date -d yesterday +\%F) && bash ml/ops/upload_archive.sh; } >> /var/log/nextroute-ml.log 2>&1
0 6 * * * cd /path/to/nextroute && docker compose run --rm nextroute-ml python retention.py --dry-run >> /var/log/nextroute-ml.log 2>&1
```

05:10 `docker compose pull`로 GHCR 최신 이미지를 받은 뒤 05:20 archive가 그 이미지로 실행되는 전제다.

Spring label batch가 04:50 KST에 끝난 뒤 05:20 KST archive가 시작되는 전제다. 05:40 작업은 전날 파티션에 대한 `validate.py`가 성공한 경우에만 Google Drive 업로드를 실행한다. v1 알림은 `/var/log/nextroute-ml.log`에서 `[error]` grep으로 확인한다.

`retention.py --apply`를 운영하기 전에는 아래 Google Drive 백업을 설정하고 최신 업로드 성공 여부를 확인해야 한다.

## Remote Backup (rclone to Google Drive)

`retention.py --apply`가 raw DB row를 삭제하기 전에 VPS archive의 오프사이트 사본을 만든다. 백업 대상은 장기 원천인 `bus_label`, `bus_position`, `bus_candidate`이며, 재생성 가능한 dataset과 model experiment는 제외한다.

VPS host에 `rclone`을 설치한다.

```bash
sudo apt update
sudo apt install rclone
rclone version
```

Ubuntu 기본 저장소의 `rclone`이 오래되어 OAuth 설정에 실패하면 공식 최신 설치 스크립트를 사용한다.

```bash
sudo -v
curl https://rclone.org/install.sh | sudo bash
rclone version
```

VPS에 브라우저가 없으면 로컬 맥에서 Google Drive OAuth 토큰을 발급한다.

```bash
rclone authorize "drive"
```

브라우저 인증 후 출력되는 token JSON을 VPS의 `rclone config`에서 `gdrive` remote를 만들 때 입력한다. 또는 `~/.config/rclone/rclone.conf`를 직접 구성할 수 있다. cron은 이 설정 파일을 소유한 동일한 VPS 사용자로 실행해야 한다.

```bash
chmod 600 ~/.config/rclone/rclone.conf
rclone lsd gdrive:
```

`rclone.conf`에는 Google Drive refresh token이 평문으로 저장된다. 이 파일은 repo 밖 `~/.config/rclone/`에만 두고 절대 커밋하지 않는다.

기본 원격 경로는 다음과 같다.

```text
gdrive:nextroute-archive/
  bus_label/
  bus_position/
  bus_candidate/
```

수동 업로드:

```bash
ML_DATA_DIR=/srv/nextroute/ml-data bash ml/ops/upload_archive.sh
rclone size gdrive:nextroute-archive
```

다른 remote나 경로를 사용하려면 `ML_RCLONE_REMOTE`를 지정한다.

```bash
ML_RCLONE_REMOTE=gdrive:other-path bash ml/ops/upload_archive.sh
```

스크립트는 `rclone copy`만 사용하므로 원격 파일을 삭제하지 않는다. 날짜별 parquet는 immutable이므로 재실행하면 같은 파일은 건너뛴다. `sync`는 원격 장기 백업 파일을 삭제할 수 있으므로 사용하지 않는다.

현재 Google Drive 용량은 200GB다. archive 증가량 약 33GB/년 기준으로 Drive가 비어 있다고 가정하면 약 6년을 보관할 수 있다. 다른 Drive 사용량을 포함한 실제 여유 공간은 `rclone about gdrive:`와 `rclone size gdrive:nextroute-archive`로 주기적으로 확인한다. 원격 archive 삭제 정책은 별도 작업으로 결정한다.

`retention.py`는 Google Drive 상태를 직접 검사하지 않는다. `--apply`를 실행하기 전에는 최신 cron 로그에 `[upload] ... all archives uploaded`가 있고 `rclone size`가 정상인지 운영자가 확인해야 한다.

Google Drive는 주간 retrain의 대체 복원 경로로도 사용할 수 있다.

```bash
rclone copy gdrive:nextroute-archive/bus_label ml/data/bus_label
rclone copy gdrive:nextroute-archive/bus_position ml/data/bus_position
rclone copy gdrive:nextroute-archive/bus_candidate ml/data/bus_candidate
```

## Weekly Retrain (자동)

`ml/ops/retrain.sh`가 VPS host crontab에서 주 1회 실행한다. Drive archive를 원천으로 **전체 누적**
재학습 후, gate 통과 시 `model/current` symlink를 원자 교체하고 serve를 재기동한다. 설계 근거는
`docs/plan/rotation-PR5-cumulative-retrain.md`.

파이프라인: flock(retention과 공유) → 디스크 gate → Drive 누락 partition restore →
날짜별 `build_dataset.py`(갭 안전) → 누적 `train.py`(콤마 날짜리스트 + `--test-dates`) →
`gate.py`(metric AND coverage) → relative symlink swap → `compose restart nextroute-ml-serve` →
`/health`·`/metadata` 확인 → dataset 삭제 + 오래된 experiment 정리.

### 환경변수

| 변수 | 의미 | 예 |
|---|---|---|
| `ML_TRAIN_FROM` | 누적 학습 시작일(첫 수집일, 필수) | `2026-06-12` |
| `ML_ABS_MAE` | 절대 MAE 상한(필수). 현 모델 `metrics.json`의 `overall.mae` 기준 설정 | `95` |
| `ML_ARCHIVE_REMOTE` | gdrive 원천 | `gdrive:nextroute-archive` |
| `ML_SAMPLE_ROWS` | 학습 행 상한(OOM 방지) | `2000000` |
| `ML_MAX_REGRESSION` | 직전 모델 대비 MAE 허용 배수 | `1.05` |
| `ML_MIN_FREE_GB` / `ML_EXTRA_FREE_GB` / `ML_DISK_FACTOR` | 디스크 gate | `8` / `5` / `2.5` |
| `ML_KEEP_EXPERIMENTS` | 보관 experiment 개수 | `5` |
| `ML_APP_DIR` / `ML_DATA_DIR` | compose dir / 데이터 host 경로 | `/root/apps/nextroute` / `/srv/nextroute/ml-data` |

### 1회성 ops — symlink 모델 경로 전환

`compose.override.yaml`의 `ML_MODEL_PATH`를 **symlink 고정 경로**로 바꾸고 현 모델로 최초 링크를 만든다.
symlink는 **relative target**이어야 serve 컨테이너 `/data:ro`에서 깨지지 않는다.

```bash
# compose.override.yaml: ML_MODEL_PATH=/data/model/current
cd /srv/nextroute/ml-data && mkdir -p model
ln -sfn ../experiments/<현재_run_id> model/current   # relative target
cd /root/apps/nextroute && docker compose up -d nextroute-ml-serve
```

### crontab (validate/upload·retention 이후, 공통 lock)

```cron
30 6 * * MON cd /root/apps/nextroute && ML_TRAIN_FROM=2026-06-12 ML_ABS_MAE=95 bash ml/ops/retrain.sh >> /var/log/nextroute-ml.log 2>&1
```

gate 실패는 current 모델을 유지하고 비치명(exit 0) 종료한다. restore/build/train/배포 실패는 non-zero로
끝나며 `/var/log/nextroute-ml.log`의 `[error]` grep으로 확인한다. `gate.py`가 이미지에 포함되려면
이 PR 머지 후 GHCR 이미지 재빌드(05:10 `docker compose pull`)가 선행돼야 한다.

### 수동 fallback

로컬에서 직접 학습하려면 archive를 rsync로 받아 동일 단계를 수동 실행한다.

```bash
rsync -av vps:/srv/nextroute/ml-data/bus_label/ ml/data/bus_label/
uv run python build_dataset.py <date>            # 날짜별
uv run python train.py <dates,csv> --target label --test-dates <latest_week> --sample-rows 2000000
uv run python gate.py --model-dir ml/data/experiments/<run> --abs-mae 95 \
  --prev-dir ml/data/experiments/<prev> --expected-routes-file <routes.txt>
```
