# PR5 — 누적 재학습 자동화 cron

주간 노선 로테이션(PR #40)의 뒷단. 로테이션이 매주 다른 30개를 수집·archive까지 자동화해도,
**train을 누적 범위로 자동 실행하는 cron이 없으면 `route_categories`가 늘지 않아** 빠진 노선
예측이 깨진다. 이 PR은 `build_dataset → train → 모델 배포 → serve 리로드`를 누적 범위로 주 1회
자동 실행한다.

## 전제 / 현황

- 기존 일배치 cron(VPS): `archive.py`(DB→parquet) + `upload_archive.sh`(rclone offsite) + `retention.py`. **train/build_dataset은 cron 제외(수동).**
- `archive` parquet은 `ML_DATA_DIR=/srv/nextroute/ml-data` 하위 `{table}/service_date=YYYY-MM-DD/`에 영속. retention은 raw DB 21일·dataset cache 7일만 삭제하고 archive parquet은 보존 → 누적 학습 안전.
- batch는 GHCR 이미지(PR #20)에서 `docker compose run`으로 CMD 오버라이드.
- serve는 `ML_MODEL_PATH`(experiment dir or model.txt)를 **기동 시 1회 로드**. 핫리로드 없음 → 배포 = 모델 경로 갱신 + serve 컨테이너 재기동.
- `route_categories`는 train 데이터에 존재하는 route_id로 자동 도출(`ml/train.py:110` `categorical_dtypes`). 별도 등록 없음.

## 목표

매주, 첫 수집일 ~ 어제까지 누적 archive로 모델을 재학습하고, 품질 게이트를 통과하면 무중단에
가깝게 교체한다. route_categories가 매 로테이션 노선의 합집합으로 단조 증가한다.

## cron이 실행하는 파이프라인 (`ml/ops/retrain.sh`)

```
0. lock 획득(flock) — 중복 실행 방지
1. 날짜범위 결정: FROM=ML_TRAIN_FROM(첫 수집일, 고정), TO=어제(KST)
2. build_dataset.py --from $FROM --to $TO   # archive parquet → dataset parquet(누적)
3. train.py <FROM..TO 날짜 리스트> --target label --sample-rows N --test-dates <holdout>
     # ⚠️ split.py:249 — 다중 service_date 학습은 --test-dates 필수.
     # holdout = 최근 7일(또는 직전 완료 주차)을 test로 분리, 나머지가 train.
     → 새 experiment dir: model.txt + training_manifest.json + metrics
4. 품질 게이트(둘 다 충족 시 배포): overall.mae <= ABS_THRESHOLD AND new_mae <= prev_mae * 1.05
     - 통과: 5로
     - 실패: 배포 스킵, 알림, 기존 모델 유지(exit 0, 비치명)
5. 배포: experiment dir를 'current' 경로로 원자 교체(rename/symlink swap)
6. serve 컨테이너 재기동: `docker compose restart nextroute-ml-serve` → ML_MODEL_PATH 재로드
7. /health·/metadata 폴링으로 로드 성공 + route_count 증가 확인
8. upload(모델 아카이브) + 로그
```

## 핵심 설계 결정

### 날짜범위 — 누적
- `ML_TRAIN_FROM`(첫 수집일)을 환경변수로 고정. `TO`=어제. 매주 범위가 자동 확장.
- train.py 다중 service_date는 **`--test-dates` 필수**(`split.py:249` `multi-date split requires --test-dates`). retrain.sh가 FROM..TO 날짜 리스트를 생성하고, holdout으로 **최근 7일(또는 직전 완료 주차)**을 `--test-dates`로 분리 → 나머지가 train.
- 학습량 상한: `--sample-rows`(기본 2M)로 캡. 범위가 커져도 OOM/시간 통제. (PR #4 OOM fix 참고)

### 모델 배포·리로드
- serve `ML_MODEL_PATH`를 안정 경로(예: `$ML_DATA_DIR/model/current`)로 고정.
- train 산출물을 `model/exp-<ts>/`에 쓰고, 게이트 통과 시 `current` symlink를 원자 교체.
- serve 재기동으로 신모델 로드(핫리로드 미지원). 교체+재기동 사이 짧은 503은 Java가 MODEL_UNAVAILABLE로 graceful degrade(기존 동작).

### 품질 게이트 (둘 다 충족해야 배포)
- train.py가 출력하는 holdout metric(label MAE)을 파싱.
- 기준: **`overall.mae <= ABS_THRESHOLD`** AND **`new_mae <= prev_mae * 1.05`**(직전 배포 모델 대비 5% 회귀 허용).
  - `ABS_THRESHOLD`는 환경변수(초기값은 현 baseline metric 기준으로 설정).
  - `prev_mae`는 직전 배포 모델의 metrics.json에서 읽음. 직전 없으면 (b) 스킵하고 (a)만.
- 실패 시 배포 스킵 + 기존 current 유지. route_categories 누락 노선이 생겨도 옛 모델로 서비스 지속.

### retention 상호작용
- retention은 archive parquet 미삭제 → 누적 train 안전.
- dataset cache 7일 삭제 → build_dataset이 매주 필요한 범위를 재생성(archive에서 저렴하게 복원).
- **순서 의존: 매일 archive가 retention(21일) 전에 완료돼야 raw 손실 없이 parquet 확보.** 기존 일배치 충족.

### 스케줄·순서
- 기존 ML cron(`ml/README.md`): 05:20 archive → 05:40 validate+upload → 06:00 retention. 재학습은 **validate/upload 이후인 월 06:10 KST 이후**(예: `10 6 * * MON`). 05:30은 validate 창과 충돌하므로 금지.
- 주기: **주 1회**. 로테이션이 주 단위라 격주면 route_categories 반영 지연이 큼.
- 로테이션(월 04:00)과 독립 — 재학습은 "과거 누적"을 학습하므로 이번 주 새 노선의 첫 데이터를 즉시 요구하지 않음. 새 노선은 그 주 수집→다음 재학습부터 route_categories 편입.

## 추가/수정 파일

- `ml/ops/retrain.sh` — 위 파이프라인. flock, 날짜계산, 게이트, symlink swap, serve restart, 검증.
- `ml/ops/README` 또는 `ml/README.md` — cron 등록 예시(crontab), 환경변수표.
- (선택) `ml/deploy.py` — experiment dir 검증 + current symlink 원자 교체 + /health 폴링(쉘 대신 파이썬으로 견고하게).
- (선택) `ml/gate.py` — metrics.json 파싱 + 기준 비교(배포 여부 0/1 반환).
- VPS crontab 1줄 추가(인프라, 코드 아님).

## 모니터링·실패 처리

- 멱등: 같은 날 재실행해도 dataset/모델 재생성만, current 교체는 게이트 통과시에만.
- flock으로 중복 방지(긴 학습이 다음 트리거와 겹침 방지).
- 실패는 비치명(exit 0) + 로그/알림 — current 모델 보존이 최우선.
- 배포 후 `/metadata` route_count가 직전 대비 **감소하면 경보**(누적인데 줄면 데이터/범위 이상).

## 후속(이 PR 밖)

- serve **reload 엔드포인트**(컨테이너 재기동 없이 모델 교체) — 이번 PR은 `compose restart`로 가고, reload는 별도 PR.
- 슬라이딩 윈도우 전환(노선별 staleness/concept drift 대응) — 누적이 학습량·drift 한계 도달 시.
- route 단위 provenance(어느 bucket/주차 데이터인지) 메타 — 디버깅·가중 학습용.
- 모델 버전 롤백 자동화.

## 검증 (배포 전)

1. 수동 1회: `retrain.sh` 로컬/스테이징 실행 → experiment dir 생성, 게이트 로그, current 교체, serve `/metadata` route_count 확인.
2. route_categories에 직전 로테이션 노선 route_id 포함되는지 확인.
3. 게이트 실패 시 current 미교체 확인(일부러 나쁜 모델로).
