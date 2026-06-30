# Local train / VPS serve 파이프라인 (PR D1)

VPS 누적 학습이 `ML_SAMPLE_ROWS=2,000,000`에서 OOM → 역할 분리.

```
VPS  : 수집 → archive.py → rclone → Drive            (daily cron, 유지)
       + serve 배포(model/current symlink + recreate)  ← 학습 안 함
LOCAL: rsync(VPS archive) → weather/holiday fetch → build_dataset
       → train(2M OK) → gate(VPS current 모델 대비) → experiment
LOCAL: experiment rsync → VPS → symlink 교체 → serve recreate → health/version → rollback
```

## 스크립트
- `ml/ops/train_local.sh` — Mac 학습 전용. `ML_TRAIN_FROM`/`ML_ABS_MAE` 필수.
  archive는 `rsync vps:`로 받고, weather/holiday는 로컬 fetch(`env/api.env`의 GONG_API),
  gate 기준 모델은 VPS `model/current` 실체를 rsync. 배포 안 함, `RUN_ID=<id>` 출력.
- `ml/ops/deploy_model.sh <run_id>` — experiment를 VPS로 rsync, `model/current` 교체,
  serve recreate(이미지 pull 없음=schema1만), `/health`+`/metadata model_version==run_id` 검증,
  실패 시 이전 symlink로 롤백.
- `ml/ops/retrain.sh` — **deprecated**(안내 후 exit 1). 위 둘로 대체.

## 운영 선행 (1회)
- VPS serve `ML_MODEL_PATH=/data/model/current`(override) + `model/current` 심볼릭(기존 모델).
- VPS retrain cron 없음(학습이 로컬로 이동).

## 사용
```bash
ML_TRAIN_FROM=2026-06-12 ML_ABS_MAE=120 bash ml/ops/train_local.sh   # → RUN_ID=...
bash ml/ops/deploy_model.sh <run_id>
```

## D2(별도)
feature 활성화 + schema 1→2. serve 이미지가 schema2 기대 → 배포 실패 시 **image 롤백**까지
필요(D1 deploy_model은 이미지 유지라 schema1 전용). D2 전용 배포 절차에서 처리.
