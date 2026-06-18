#!/usr/bin/env bash
# Upload bus ML archive parquet to a remote offsite truth copy.
# rclone copy never deletes remote files. Run on the VPS host after validation.
set -euo pipefail

DATA_DIR="${ML_DATA_DIR:-/srv/nextroute/ml-data}"
REMOTE="${ML_RCLONE_REMOTE:-gdrive:nextroute-archive}"
ARCHIVES=(bus_label bus_position bus_candidate)

ts() {
  date +%FT%T%z
}

if ! command -v rclone >/dev/null 2>&1; then
  echo "[error] $(ts) rclone is not installed" >&2
  exit 1
fi

if [[ ! -d "$DATA_DIR" ]]; then
  echo "[error] $(ts) data directory missing: $DATA_DIR" >&2
  exit 1
fi

echo "[upload] $(ts) start data_dir=$DATA_DIR remote=$REMOTE"

fail=0
found=0
for name in "${ARCHIVES[@]}"; do
  src="$DATA_DIR/$name"
  if [[ ! -d "$src" ]]; then
    echo "[upload] $(ts) skip $name: $src missing"
    continue
  fi

  found=$((found + 1))
  if rclone copy "$src" "$REMOTE/$name" --transfers 4 --checkers 8; then
    echo "[upload] $(ts) done $name"
  else
    echo "[error] $(ts) upload $name failed" >&2
    fail=1
  fi
done

if [[ "$found" -eq 0 ]]; then
  echo "[error] $(ts) no archive directories found under $DATA_DIR" >&2
  exit 1
fi

if [[ "$fail" -ne 0 ]]; then
  echo "[error] $(ts) upload finished with failures" >&2
  exit 1
fi

echo "[upload] $(ts) all archives uploaded"
