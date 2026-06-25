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
- train.py 다중 service_date는 **`--test-dates` 필수**(`ml/split.py:249` `multi-date split requires --test-dates`). holdout=**직전 완료 주차(또는 최근 7일)**를 test로 분리.

## 재정의된 파이프라인 (`ml/ops/retrain.sh`, 주1회)

```
weekly retrain (월 06:10 KST+, validate/upload 이후)
 → flock (중복 실행 방지)
 → rclone size gdrive:nextroute-archive            # 원천 크기 파악
 → df 디스크 gate: free >= ML_MIN_FREE_GB
        (+ dataset 생성 여유 ML_EXTRA_FREE_GB 확보) 아니면 alert+non-zero
 → Drive에서 누락 archive partition restore         # 로컬 갭 메움 (rclone copy, ML_ARCHIVE_REMOTE)
 → dataset dir cleanup (이전 잔여 제거)
 → build_dataset.py --from $ML_TRAIN_FROM --to $TO --overwrite   # 누적 dataset 생성
 → train.py <FROM..TO 날짜리스트> --target label --test-dates <직전주차> --sample-rows N
        → experiments/lgbm_<from>_to_<to>_label_<ts>/ : model.txt + manifest + metrics
 → metric gate (둘 다): overall.mae <= ML_ABS_MAE  AND  new_mae <= prev_mae * 1.05
 → route coverage gate: 신모델 route_categories ⊇ 기대 route 집합          # ★ 아래 리스크 참고
 → /data/model/current symlink atomic swap (신 experiment dir로)
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
- 이후 retrain은 `current` symlink만 원자 교체(`ln -sfn <new> /data/model/current.tmp && mv -T`) + `docker compose restart nextroute-ml-serve`. serve는 `:ro`라도 symlink 따라 읽기 OK.
- rollback = symlink를 직전 experiment dir로 되돌리고 restart.

## DB retention 연동

- retention apply는 **archive+upload 검증 성공 이후에만**. 현재 cron은 `--dry-run`(미적용) → apply 전환 시 upload 성공 게이트 선행 필수(parquet이 gdrive에 올라간 뒤 raw 삭제). 순서: archive → validate → upload(gdrive) → 그 성공 확인 후 retention --apply.

## ★ 코드 리스크 — sample-rows vs route coverage

`--sample-rows`는 OOM 방지에 필요하나(누적이라 행 급증), 너무 낮으면 **일부 route가 학습 category에서
빠질 수 있다**. 현 sampling은 route/horizon을 고려하지만(`split.py` bucket 단위), 누적 날짜·route가
많아질수록 route당 행이 희박해짐. → **retrain 검증에 `route coverage gate` 필수**:
신모델 `/metadata.route_categories`가 **기대 route 집합(누적 수집된 전 route)을 포함**하는지 확인,
누락 시 alert(또는 sample-rows 상향 후 재시도). 단순 route_count 비감소보다 강한 보증.

## 추가/수정 파일

- `ml/ops/retrain.sh` — 메인 파이프라인(flock, 디스크 gate, restore, build, train, gate, swap, restart, cleanup).
- `ml/ops/restore_archive_from_drive.sh` — `rclone copy gdrive:nextroute-archive → /data`로 누락 partition 복원(또는 retrain.sh 내부 함수).
- `ml/deploy.py` (선택) — symlink 원자 교체 + /health·/metadata 폴링 + route coverage 검증(쉘보다 견고).
- `ml/gate.py` (선택) — metrics.json 파싱 + (절대 임계 AND 직전대비) 판정 0/1.
- `ml/README.md` — 환경변수표 + crontab 1줄 추가.
- `compose.override.yaml` — `ML_MODEL_PATH=/data/model/current` 전환(ops, 코드 아님).

## 환경변수

| 변수 | 의미 | 예 |
|---|---|---|
| `ML_TRAIN_FROM` | 누적 학습 시작일(첫 수집일) | `2026-06-12` |
| `ML_ARCHIVE_REMOTE` | gdrive 원천 | `gdrive:nextroute-archive` |
| `ML_MIN_FREE_GB` | 최소 여유 디스크 게이트 | `8` |
| `ML_EXTRA_FREE_GB` | dataset 생성용 추가 여유 | `5` |
| `ML_ABS_MAE` | 절대 MAE 임계 | (현 baseline metric 기준) |
| `ML_MODEL_PATH` | serve 모델 경로(symlink로 전환) | `/data/model/current` |
| `ML_KEEP_EXPERIMENTS` | 보관할 experiment 개수 | `5` |

## 스케줄

- 재학습: `10 6 * * MON`(월 06:10 KST) — validate/upload(05:40)·retention(06:00) 이후. 05:30 금지(validate 창 충돌).
- 주 1회 유지(로테이션 주기와 정렬, 격주는 반영 지연).

## 후속(이 PR 밖)

- serve **reload 엔드포인트**(컨테이너 재기동 없이 교체) — 이번 PR은 `compose restart`.
- 모델 버전 롤백 자동화(현재는 수동 symlink 되돌림).
- route provenance 메타(bucket/주차) — 가중 학습·디버깅.

## 검증 (배포 전)

1. 수동 1회 VPS 실행: `retrain.sh` → restore·build·train 로그, gate 판정, symlink 교체, serve `/metadata` route_count.
2. **route coverage**: `/metadata.route_categories` ⊇ 누적 수집 route 전체 확인.
3. gate 실패 시 current 미교체 + 비치명 종료 확인.
4. restore/build/train 실패 시 non-zero + current 보존 확인.
5. dataset/가 retrain 후 삭제됐는지 디스크 확인.
