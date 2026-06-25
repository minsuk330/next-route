#!/usr/bin/env bash
# Weekly cumulative retrain + atomic model deploy. Runs on the VPS host
# (rclone + symlink + docker compose live on the host; python steps run inside
# the nextroute-ml batch container). See docs/plan/rotation-PR5-cumulative-retrain.md.
#
# Pipeline: flock → disk gate → Drive restore (gap-safe) → per-date build_dataset
#   → cumulative train (comma date-list, --test-dates) → gate.py
#   → relative symlink swap → serve restart → health/coverage check → cleanup.
#
# Failure semantics:
#   restore/build/train/deploy fail → non-zero exit (alert).
#   gate fail                       → keep current, exit 0 (non-fatal, skip deploy).
set -euo pipefail

ts() { date +"%Y-%m-%dT%H:%M:%S%z"; }
log() { echo "[retrain] $(ts) $*"; }
err() { echo "[error] $(ts) $*" >&2; }

# ── config (env-overridable) ────────────────────────────────────────────────
APP_DIR="${ML_APP_DIR:-/root/apps/nextroute}"
DATA_DIR_HOST="${ML_DATA_DIR:-/srv/nextroute/ml-data}"     # host path
DATA_DIR_CTR="/data"                                       # container mount
ARCHIVE_REMOTE="${ML_ARCHIVE_REMOTE:-gdrive:nextroute-archive}"
TRAIN_FROM="${ML_TRAIN_FROM:?set ML_TRAIN_FROM=YYYY-MM-DD (first collection date)}"
ABS_MAE="${ML_ABS_MAE:?set ML_ABS_MAE (absolute MAE ceiling)}"
MAX_REGRESSION="${ML_MAX_REGRESSION:-1.05}"
SAMPLE_ROWS="${ML_SAMPLE_ROWS:-2000000}"
TARGET="${ML_TARGET:-label}"
KEEP_EXPERIMENTS="${ML_KEEP_EXPERIMENTS:-5}"
MIN_FREE_GB="${ML_MIN_FREE_GB:-8}"
EXTRA_FREE_GB="${ML_EXTRA_FREE_GB:-5}"
DISK_FACTOR="${ML_DISK_FACTOR:-2.5}"
SERVE_CONTAINER="${ML_SERVE_CONTAINER:-nextroute-ml-serve}"
SERVE_HEALTH_URL="${ML_SERVE_HEALTH_URL:-http://localhost:8001}"
LOCK_FILE="${ML_LOCK_FILE:-/var/lock/nextroute-ml.lock}"   # shared with retention

MODEL_DIR_HOST="$DATA_DIR_HOST/model"
EXP_DIR_HOST="$DATA_DIR_HOST/experiments"
TODAY="$(date +%F)"
TO="$(date -d yesterday +%F)"

cd "$APP_DIR"
DC=(docker compose)
# batch container, rw data mount, repo workdir; --rm one-shot.
dcrun() { "${DC[@]}" run --rm -T nextroute-ml "$@"; }

# ── 0. lock (shared with retention so a long apply never overlaps) ───────────
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
	err "another nextroute-ml job holds $LOCK_FILE; skipping retrain"
	exit 0
fi

# ── 1. disk gate ────────────────────────────────────────────────────────────
REMOTE_BYTES="$(rclone size --json "$ARCHIVE_REMOTE" 2>/dev/null | sed -n 's/.*"bytes":\([0-9]*\).*/\1/p')"
REMOTE_BYTES="${REMOTE_BYTES:-0}"
FREE_BYTES="$(df -B1 --output=avail "$DATA_DIR_HOST" | tail -1 | tr -d ' ')"
NEED_BYTES="$(awk -v r="$REMOTE_BYTES" -v f="$DISK_FACTOR" -v e="$EXTRA_FREE_GB" -v m="$MIN_FREE_GB" \
	'BEGIN{need=r*f + e*1073741824; floor=m*1073741824; print (need>floor?need:floor)}')"
log "disk: free=$FREE_BYTES need=$NEED_BYTES (remote=$REMOTE_BYTES factor=$DISK_FACTOR)"
if [ "$FREE_BYTES" -lt "${NEED_BYTES%.*}" ]; then
	err "insufficient disk: free $FREE_BYTES < need $NEED_BYTES"
	exit 1
fi

# ── 2. restore: Drive available partitions → local (gap-safe, only missing) ──
# available_service_dates = partitions present on Drive for the spine table.
mapfile -t AVAIL_DATES < <(
	rclone lsf "$ARCHIVE_REMOTE/bus_label/" 2>/dev/null \
		| sed -n 's#^service_date=\([0-9-]\{10\}\)/$#\1#p' \
		| awk -v from="$TRAIN_FROM" -v to="$TO" '$1>=from && $1<=to' \
		| sort
)
if [ "${#AVAIL_DATES[@]}" -eq 0 ]; then
	err "no archive partitions on $ARCHIVE_REMOTE in [$TRAIN_FROM..$TO]"
	exit 1
fi
log "available dates: ${#AVAIL_DATES[@]} ($TRAIN_FROM..$TO)"

for tbl in bus_label bus_position bus_candidate; do
	for d in "${AVAIL_DATES[@]}"; do
		local_part="$DATA_DIR_HOST/$tbl/service_date=$d/data.parquet"
		[ -f "$local_part" ] && continue
		log "restore $tbl $d"
		rclone copy "$ARCHIVE_REMOTE/$tbl/service_date=$d/" \
			"$DATA_DIR_HOST/$tbl/service_date=$d/" --transfers 4 --checkers 8 \
			|| { err "restore failed $tbl $d"; exit 1; }
	done
done

# ── 3. dataset cleanup (ephemeral) ──────────────────────────────────────────
rm -rf "$DATA_DIR_HOST/dataset"
mkdir -p "$DATA_DIR_HOST/dataset"

# ── 4. build_dataset per available date (range input fails on gap days) ─────
for d in "${AVAIL_DATES[@]}"; do
	log "build_dataset $d"
	dcrun python build_dataset.py "$d" --overwrite || { err "build_dataset failed $d"; exit 1; }
done

# ── 5. cumulative train (comma list of available dates) ─────────────────────
DATE_CSV="$(IFS=,; echo "${AVAIL_DATES[*]}")"
# holdout = last available week, kept so every route still has train rows upstream.
TEST_DATES="$(printf '%s\n' "${AVAIL_DATES[@]}" | tail -7 | paste -sd, -)"
log "train dates=${#AVAIL_DATES[@]} test=$TEST_DATES sample_rows=$SAMPLE_ROWS"
dcrun python train.py "$DATE_CSV" --target "$TARGET" --test-dates "$TEST_DATES" \
	--sample-rows "$SAMPLE_ROWS" || { err "train failed"; exit 1; }

# newest experiment dir (train wrote it).
RUN_DIR_HOST="$(ls -1dt "$EXP_DIR_HOST"/*/ 2>/dev/null | head -1)"
RUN_DIR_HOST="${RUN_DIR_HOST%/}"
RUN_ID="$(basename "$RUN_DIR_HOST")"
[ -f "$RUN_DIR_HOST/model.txt" ] || { err "no model.txt in $RUN_DIR_HOST"; exit 1; }
log "trained: $RUN_ID"

# ── 6. expected routes = distinct route_id in built dataset (must be trained) ─
mkdir -p "$DATA_DIR_HOST/tmp"
dcrun python -c "
import polars as pl, glob
files = glob.glob('$DATA_DIR_CTR/dataset/service_date=*/part-*.parquet')
routes = pl.scan_parquet(files).select('route_id').unique().collect()['route_id'].to_list()
open('$DATA_DIR_CTR/tmp/expected_routes.txt','w').write('\n'.join(sorted(str(r) for r in routes if r is not None)))
" || { err "expected-routes extraction failed"; exit 1; }

# ── 7. gate (metric AND coverage). gate fail = keep current, non-fatal. ─────
PREV_TARGET=""
[ -L "$MODEL_DIR_HOST/current" ] && PREV_TARGET="$(readlink "$MODEL_DIR_HOST/current")"
PREV_ARG=()
[ -n "$PREV_TARGET" ] && PREV_ARG=(--prev-dir "$DATA_DIR_CTR/model/$PREV_TARGET")

set +e
dcrun python gate.py \
	--model-dir "$DATA_DIR_CTR/experiments/$RUN_ID" \
	"${PREV_ARG[@]}" \
	--abs-mae "$ABS_MAE" --max-regression "$MAX_REGRESSION" \
	--expected-routes-file "$DATA_DIR_CTR/tmp/expected_routes.txt"
GATE_RC=$?
set -e
if [ "$GATE_RC" -eq 2 ]; then
	err "gate failed — keeping current model ($PREV_TARGET), skipping deploy"
	rm -rf "$DATA_DIR_HOST/dataset"
	exit 0
elif [ "$GATE_RC" -ne 0 ]; then
	err "gate error rc=$GATE_RC"
	exit 1
fi

# ── 8. deploy: relative symlink atomic swap (works inside /data:ro serve) ────
mkdir -p "$MODEL_DIR_HOST"
ln -sfn "../experiments/$RUN_ID" "$MODEL_DIR_HOST/current.tmp"
mv -T "$MODEL_DIR_HOST/current.tmp" "$MODEL_DIR_HOST/current"
log "symlink current → ../experiments/$RUN_ID"

# ── 9. serve restart + health ───────────────────────────────────────────────
"${DC[@]}" restart "$SERVE_CONTAINER" || { err "serve restart failed"; exit 1; }
ok=0
for _ in $(seq 1 30); do
	if curl -fsS "$SERVE_HEALTH_URL/health" >/dev/null 2>&1; then ok=1; break; fi
	sleep 2
done
if [ "$ok" -ne 1 ]; then
	err "serve unhealthy after deploy — rolling back to $PREV_TARGET"
	if [ -n "$PREV_TARGET" ]; then
		ln -sfn "$PREV_TARGET" "$MODEL_DIR_HOST/current.tmp"
		mv -T "$MODEL_DIR_HOST/current.tmp" "$MODEL_DIR_HOST/current"
		"${DC[@]}" restart "$SERVE_CONTAINER" || true
	fi
	exit 1
fi
ROUTE_COUNT="$(curl -fsS "$SERVE_HEALTH_URL/metadata" | sed -n 's/.*"route_count":[ ]*\([0-9]*\).*/\1/p')"
log "deployed $RUN_ID, /metadata route_count=$ROUTE_COUNT"

# ── 10. cleanup: ephemeral dataset + old experiments (keep last N) ──────────
rm -rf "$DATA_DIR_HOST/dataset"
ls -1dt "$EXP_DIR_HOST"/*/ 2>/dev/null | tail -n +"$((KEEP_EXPERIMENTS + 1))" | while read -r old; do
	old="${old%/}"
	[ "$(basename "$old")" = "$RUN_ID" ] && continue
	# never delete the current target
	[ -n "$PREV_TARGET" ] && [ "$(basename "$old")" = "$(basename "$PREV_TARGET")" ] && continue
	log "prune old experiment $(basename "$old")"
	rm -rf "$old"
done

log "retrain done: $RUN_ID"
