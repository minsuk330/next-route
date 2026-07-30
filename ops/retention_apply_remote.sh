#!/usr/bin/env bash
# Host-side guarded retention apply.
#
# Why host-side: rclone and its Google Drive credentials live on the VPS host,
# while retention.py runs inside the nextroute-ml container. This wrapper runs a
# container dry-run, verifies every gated service_date exists on the remote
# archive, then runs the destructive apply only after the remote check passes.
set -euo pipefail

ts() {
  date +%FT%T%z
}

log() {
  echo "[retention-remote] $(ts) $*"
}

err() {
  echo "[error] $(ts) $*" >&2
}

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ML_COMPOSE_FILE:-$REPO_ROOT/compose.yaml}"
DATA_DIR="${ML_DATA_DIR:-/srv/nextroute/ml-data}"
REMOTE="${ML_RCLONE_REMOTE:-gdrive:nextroute-archive}"
RETENTION_DAYS="${ML_RETENTION_DAYS:-7}"
DATASET_CACHE_DAYS="${ML_DATASET_CACHE_DAYS:-7}"
CHUNK_SIZE="${ML_RETENTION_CHUNK_SIZE:-10000}"
SERVICE="${ML_RETENTION_SERVICE:-nextroute-ml}"
TABLES=(bus_label bus_position bus_candidate)

command -v rclone >/dev/null 2>&1 || {
  err "rclone is not installed on host"
  exit 1
}

command -v python3 >/dev/null 2>&1 || {
  err "python3 is required on host"
  exit 1
}

if [[ ! -d "$DATA_DIR" ]]; then
  err "data dir missing: $DATA_DIR"
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  err "compose file missing: $COMPOSE_FILE"
  exit 1
fi

cd "$REPO_ROOT"

log "preflight dry-run retention_days=$RETENTION_DAYS dataset_cache_days=$DATASET_CACHE_DAYS"
docker compose -f "$COMPOSE_FILE" run --rm -T "$SERVICE" python retention.py \
  --dry-run \
  --retention-days "$RETENTION_DAYS" \
  --dataset-cache-days "$DATASET_CACHE_DAYS" \
  --chunk-size "$CHUNK_SIZE"

REPORT="$DATA_DIR/retention/latest.json"
if [[ ! -f "$REPORT" ]]; then
  err "retention dry-run report missing: $REPORT"
  exit 1
fi

dates=()
while IFS= read -r service_date; do
  [[ -n "$service_date" ]] && dates+=("$service_date")
done < <(
  REPORT="$REPORT" python3 - <<'PY'
import json
import os

with open(os.environ["REPORT"], encoding="utf-8") as f:
    report = json.load(f)
for item in report.get("gated_service_dates", []):
    print(item)
PY
)

remote_root="${REMOTE%/}"
log "remote verify remote=$remote_root dates=${#dates[@]}"
for service_date in "${dates[@]}"; do
  for table in "${TABLES[@]}"; do
    remote_dir="$remote_root/$table/service_date=$service_date"
    files="$(rclone lsf "$remote_dir/" --files-only 2>/dev/null || true)"
    if ! grep -qx "data.parquet" <<<"$files"; then
      err "remote backup missing data.parquet: $remote_dir/"
      exit 1
    fi
    if ! grep -qx "manifest.json" <<<"$files"; then
      err "remote backup missing manifest.json: $remote_dir/"
      exit 1
    fi
  done
done
log "remote backup verified for ${#dates[@]} gated service_date(s)"

log "apply retention"
docker compose -f "$COMPOSE_FILE" run --rm -T "$SERVICE" python retention.py \
  --apply \
  --confirm-backup-reviewed \
  --retention-days "$RETENTION_DAYS" \
  --dataset-cache-days "$DATASET_CACHE_DAYS" \
  --chunk-size "$CHUNK_SIZE"

log "done"
