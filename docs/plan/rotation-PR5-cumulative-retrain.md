# PR5 — Drive archive 기반 전체 누적 재학습 + 모델 배포 자동화

주간 노선 로테이션(PR #40)의 뒷단. "수동 학습 자동화"가 아니라 **Google Drive archive를 장기
원천으로 한 전체 누적 재학습 + dataset 임시 처리 + 모델 무중단 교체**로 정의한다. 로테이션이 매주
다른 30개를 수집·archive해도, train을 **전체 누적** 범위로 자동 실행해야 `route_categories`가 단조
증가해 빠진 노선 예측이 유지된다.

## VPS 운영 현황 (실측 2026-06-25)

- 배포 디렉터리: `/root/apps/nextroute/` (`compose.yaml` + `compose.override.yaml`)
- 데이터 마운트: `/srv/nextroute/ml-data` → batch `nextroute-ml`은 `/data`(rw), serve `nextroute-ml-serve`는 `/data:ro`
- **모델 경로: `compose.override.yaml`에 `ML_MODEL_PATH=/data/experiments/lgbm_2026-06-12_to_2026-06-17_label_20260619T141946Z` 하드코딩** (symlink 아님, 특정 dir 직결). 현 모델은 로컬(mac uid 501) 수동 학습 후 rsync.
- 디스크: 58G 중 **17G 여유(72% 사용)**. `dataset/`이 4일치만으로 **767M** → 누적 dataset 영속은 디스크 파탄. archive 합계 ~767M(candidate 315M+label 238M+position 214M).
- **로컬 archive에 갭 존재**(`bus_label`에 2026-06-13·14 없음) → 로컬은 working copy일 뿐, **gdrive가 원천**임을 입증.
- rclone 원격 `gdrive:` 존재. `upload_archive.sh`가 `gdrive:nextroute-archive`로 no-delete 업로드.
- 기존 crontab(KST): `05:20 archive` → `05:40 validate + upload_archive.sh` → `06:00 retention.py --dry-run`(현재 **dry-run, 미적용**).
- serve는 `ML_MODEL_PATH`(experiment dir)를 **기동 시 1회 로드**. 핫리로드 없음 → 배포 = 경로 갱신 + serve 재기동.
- `route_categories`는 train 데이터에 존재하는 route_id로 자동 도출(`ml/train.py:110` `categorical_dtypes`). 별도 등록 없음.

## 데이터 수명 정책 (재정의)

| 자산 | 위치 | 정책 |
|---|---|---|
| DB raw | postgres | archive+upload **검증 성공 후에만** 정기 삭제(retention apply) |
| archive parquet (원천) | `gdrive:nextroute-archive` | 영속, no-delete |
| archive parquet (working) | VPS `/data/{bus_*}` | 디스크 허용 동안 유지. 누락분은 retrain 시 Drive에서 복원 |
| dataset parquet | VPS `/data/dataset` | **ephemeral** — retrain 중에만 생성, **학습 후 삭제** |
| model experiment dir | VPS `/data/experiments/*` | 최근 N개 유지(나머지 정리). `current` symlink가 운영 모델 |

## 학습 범위 — 전체 누적 고정

- `ML_TRAIN_FROM`(첫 수집일) 고정, `TO`=어제(KST). 매주 범위 자동 확장.
- **rolling window 부적합**: 주차별 route rotation이라 윈도우로 자르면 과거 bucket route가 데이터에서 빠져 `route_categories`에서 드롭. 반드시 전체 누적.
- **갭 처리(필수)**: 운영 데이터에 누락 날짜 존재(VPS·Drive 모두 2026-06-13·14 등). `build_dataset.py --from/--to`는 범위 내 전 캘린더일 archive를 요구(`require_archive` raise)하므로 **범위 입력 금지**. Drive에 실제 존재하는 `available_service_dates`만 골라 **날짜별 loop**로 build, train.py에는 **available 날짜 콤마리스트**를 넘긴다(train.py는 `parse_service_dates`로 콤마리스트 지원, 범위 아님).
- train.py 다중 service_date는 **`--test-dates` 필수**(`ml/split.py:249`).
- **holdout 선택 주의(★)**: `cat_dtypes = categorical_dtypes([train, test])`(train.py:175)라 categories=train∪test. booster는 train만 학습 → **신규 route가 전부 test로 빠지면 route_categories엔 있으나 미학습**. holdout은 **route별 train row ≥ 1**이 남도록 잡고, train.py가 **`training_route_categories`(train에 실제 포함된 route)를 manifest에 기록**하게 한다. coverage gate는 이 값을 검사(아래 ★).

## 재정의된 파이프라인 (`ml/ops/retrain.sh`, 주1회)

```
weekly retrain (월 06:30 KST+, validate/upload·retention 이후 / retention과 공통 lock)
 → flock (retention과 공유 — 06:00 retention apply가 길어져도 겹치지 않음)
 → REMOTE_SIZE=rclone size gdrive:nextroute-archive
 → df 디스크 gate: free >= REMOTE_SIZE * FACTOR + ML_EXTRA_FREE_GB (최소 ML_MIN_FREE_GB)
        아니면 alert + non-zero
 → available_service_dates = Drive에 실제 존재하는 파티션 날짜 목록 (rclone lsf)
 → 누락 partition restore: available 중 로컬에 없는 것만 rclone copy (ML_ARCHIVE_REMOTE)
 → dataset dir cleanup (이전 잔여 제거)
 → build_dataset: available_service_dates 날짜별 loop 실행 (--from/--to 범위 금지 — 갭일 require_archive 실패)
 → train.py "<available_dates 콤마리스트>" --target label --test-dates <holdout> --sample-rows N
        # holdout은 route별 train row >= 1 보장하도록 선택 (아래 ★ 참고)
        # train.py manifest에 training_route_categories(=train에 실제 포함된 route) 기록
        → experiments/lgbm_<min>_to_<max>_label_<ts>/ : model.txt + manifest + metrics
 → metric gate (둘 다): overall.mae <= ML_ABS_MAE  AND  new_mae <= prev_mae * 1.05
 → route coverage gate: manifest.training_route_categories ⊇ 기대 route 집합   # metadata 아님! ★
 → current symlink atomic swap: relative target (../experiments/<run_id>)       # ★ :ro 컨테이너 안전
 → docker compose restart nextroute-ml-serve
 → /health 200 + /metadata route_count 비감소 확인
 → dataset/ 삭제 (ephemeral)
 → 실패 시 current 유지 또는 rollback(직전 symlink 타깃 복원)
 → 오래된 experiments 정리(최근 N개만)
```

## 실패 처리 — 단계별 exit 코드 구분

| 단계 | 실패 시 |
|---|---|
| restore / build_dataset / train | **non-zero exit + alert marker** (치명: 새 모델 못 만듦) |
| metric gate / coverage gate | **current 유지**, 배포 스킵, alert (비치명, exit 0 허용) |
| symlink swap / serve restart / health | rollback(직전 symlink 복원) + non-zero |

→ 기존 플랜의 "전부 exit 0"은 폐기. gate 실패만 비치명, restore/build/train/배포 실패는 non-zero.

## 모델 배포 — symlink 전환 (1회성 ops)

- **현재**: `compose.override.yaml`이 특정 experiment dir 직결. 자동 교체 불가.
- **PR5 전환**: `ML_MODEL_PATH=/data/model/current`(symlink)로 override 1회 수정 + 최초 symlink를 현 모델로 생성.
- **symlink는 반드시 relative target**(예: `model/current → ../experiments/<run_id>`). host 절대경로(`/srv/nextroute/ml-data/experiments/...`)로 만들면 serve 컨테이너 `/data:ro` 안에서 그 경로가 없어 **깨짐**. relative면 `/data/model/current → /data/experiments/<run_id>`로 정상 해석.
  - 교체: `ln -sfn ../experiments/<run_id> /data/model/current.tmp && mv -T /data/model/current.tmp /data/model/current` (원자) + `docker compose restart nextroute-ml-serve`.
- rollback = symlink를 직전 experiment dir(relative)로 되돌리고 restart.

## DB retention 연동

- retention apply는 **archive+upload 검증 성공 이후에만**. 현재 cron은 `--dry-run`(미적용) → apply 전환 시 upload 성공 게이트 선행 필수(parquet이 gdrive에 올라간 뒤 raw 삭제). 순서: archive → validate → upload(gdrive) → 그 성공 확인 후 retention --apply.

## ★ 코드 리스크 — route_categories ≠ "학습된 route"

두 경로로 **route_categories에 있으나 실제 미학습**인 route가 생긴다:
1. **holdout 분리**: `cat_dtypes=categorical_dtypes([train,test])`(train.py:175) → categories=train∪test. 신규 route가 전부 test로 빠지면 categories엔 있지만 booster(train만 학습)는 미학습.
2. **sample-rows 과소**: 누적이라 행 급증 → `--sample-rows` 너무 낮으면 route당 train row가 0이 될 수 있음.

→ **route_categories(=metadata)는 신뢰 불가.** train.py가 **`training_route_categories`(train split에 실제 ≥1행 있는 route)**를 manifest에 기록하고, **coverage gate는 그 값**이 기대 route 집합(누적 수집 전 route)을 포함하는지 검사한다. 누락 시 alert + (holdout 재선택 또는 sample-rows 상향 후) 재시도. metadata route_count 비감소만으론 부족.

## 추가/수정 파일

- `ml/ops/retrain.sh` — 메인 파이프라인(flock, 디스크 gate, restore, available-dates loop build, train, gate, relative symlink swap, restart, dataset/experiments cleanup).
- `ml/ops/restore_archive_from_drive.sh` — `rclone lsf`로 available 날짜 산출 + 로컬 누락분만 `rclone copy gdrive:nextroute-archive → /data` 복원(또는 retrain.sh 내부 함수).
- **`ml/train.py` 수정** — manifest에 `training_route_categories`(train split의 unique route_id) 추가. coverage gate 근거.
- `ml/deploy.py` (선택) — relative symlink 원자 교체 + /health·/metadata 폴링 + coverage 검증(쉘보다 견고).
- `ml/gate.py` (선택) — metrics.json 파싱 + (절대 임계 AND 직전대비) + coverage 판정 0/1.
- `ml/README.md` — 환경변수표 + crontab 1줄 추가.
- `compose.override.yaml` — `ML_MODEL_PATH=/data/model/current` 전환(ops, 코드 아님).

## 환경변수

| 변수 | 의미 | 예 |
|---|---|---|
| `ML_TRAIN_FROM` | 누적 학습 시작일(첫 수집일) | `2026-06-12` |
| `ML_ARCHIVE_REMOTE` | gdrive 원천 | `gdrive:nextroute-archive` |
| `ML_MIN_FREE_GB` | 최소 여유 디스크 하한 | `8` |
| `ML_EXTRA_FREE_GB` | dataset 생성용 추가 여유 | `5` |
| `ML_DISK_FACTOR` | remote archive 크기 배수(restore+dataset 추산) | `2.5` |
| `ML_ABS_MAE` | 절대 MAE 임계 | (현 baseline metric 기준) |
| `ML_MODEL_PATH` | serve 모델 경로(symlink로 전환) | `/data/model/current` |
| `ML_KEEP_EXPERIMENTS` | 보관할 experiment 개수 | `5` |

## 스케줄

- 재학습: `30 6 * * MON`(월 06:30 KST+) — validate/upload(05:40)·retention(06:00) 이후. 06:00 retention apply가 길어지면 06:10과 겹칠 수 있어 **06:30 이상 + retention/retrain 공통 flock** 권장. 05:30 금지(validate 창 충돌).
- 주 1회 유지(로테이션 주기와 정렬, 격주는 반영 지연).

## 후속(이 PR 밖)

- serve **reload 엔드포인트**(컨테이너 재기동 없이 교체) — 이번 PR은 `compose restart`.
- 모델 버전 롤백 자동화(현재는 수동 symlink 되돌림).
- route provenance 메타(bucket/주차) — 가중 학습·디버깅.

## 검증 (배포 전)

1. 수동 1회 VPS 실행: `retrain.sh` → restore·build·train 로그, gate 판정, symlink 교체, serve `/metadata` route_count.
2. **route coverage**: `manifest.training_route_categories`(metadata 아님) ⊇ 누적 수집 route 전체 확인. 신규 route가 test-only/sample 누락으로 미학습되지 않았는지.
3. gate 실패 시 current 미교체 + 비치명 종료 확인.
4. restore/build/train 실패 시 non-zero + current 보존 확인.
5. dataset/가 retrain 후 삭제됐는지 디스크 확인.
