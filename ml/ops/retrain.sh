#!/usr/bin/env bash
# DEPRECATED. Training moved off the VPS (it OOM'd at ML_SAMPLE_ROWS=2,000,000).
# Train locally, then deploy:
#   ml/ops/train_local.sh           # on the dev machine
#   ml/ops/deploy_model.sh <run_id> # ship the resulting experiment to the VPS
set -euo pipefail
echo "[error] ml/ops/retrain.sh is deprecated. Run ml/ops/train_local.sh locally, then ml/ops/deploy_model.sh <run_id>." >&2
exit 1
