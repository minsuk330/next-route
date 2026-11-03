#!/usr/bin/env bash
# Local cumulative train (Mac). Pulls archive from the VPS via rsync, builds the
# dataset, trains + gates, and leaves an experiment dir. Does NOT deploy — run
# ml/ops/deploy_model.sh <run_id> afterwards. See docs/plan/local-train-vps-serve.md.
#
# Why local: VPS OOMs at ML_SAMPLE_ROWS=2,000,000 and gets worse as the archive
# grows. Training moves to the dev machine (more RAM); the VPS only serves.
set -euo pipefail

ts() { date +"%Y-%m-%dT%H:%M:%S%z"; }
log() { echo "[train-local] $(ts) $*"; }
err() { echo "[error] $(ts) $*" >&2; }

# ── repo root + absolute data dir (scripts default to ml/data; pin it) ──────
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export DATA_DIR="$REPO_ROOT/ml/data"

# ── config (env-overridable) ────────────────────────────────────────────────
TRAIN_FROM="${ML_TRAIN_FROM:?set ML_TRAIN_FROM=YYYY-MM-DD (first collection date)}"
ABS_MAE="${ML_ABS_MAE:?set ML_ABS_MAE (absolute MAE ceiling)}"
MAX_REGRESSION="${ML_MAX_REGRESSION:-1.05}"
SAMPLE_ROWS="${ML_SAMPLE_ROWS:-2000000}"
# label 타깃은 ~35%가 API ETA 추정값이라 오염돼 있다(docs/api-eta-leakage.md §5).
TARGET="${ML_TARGET:-corrected}"
TARGET_ENCODING="${ML_TARGET_ENCODING:-false}"
# 노선마다 자기 수집 기간의 마지막 N일을 test로 둔다(주간 로테이션이라 날짜 cutoff는 노선 전체를 test로 보냄).
HOLDOUT_DAYS="${ML_HOLDOUT_DAYS_PER_ROUTE:-1}"
VPS_HOST="${ML_VPS_HOST:-vps}"
VPS_DATA="${ML_VPS_DATA:-/srv/nextroute/ml-data}"
ALLOW_NO_PREV="${ML_ALLOW_NO_PREV:-false}"
# python runner: uv run guarantees the train extra (.venv); override with ML_PYTHON.
PY=(${ML_PYTHON:-uv run python})

# yesterday (BSD date on macOS, GNU fallback for Linux).
TO="$(date -v-1d +%F 2>/dev/null || date -d yesterday +%F)"
FROM_YEAR="${TRAIN_FROM:0:4}"
TO_YEAR="${TO:0:4}"

pyrun() { ( cd "$REPO_ROOT/ml" && "${PY[@]}" "$@" ); }

# ── 1. GONG_API from env/api.env (weather/holiday fetch need it; ml/.env lacks it) ─
if [ -f "$REPO_ROOT/env/api.env" ]; then
	set -a; . "$REPO_ROOT/env/api.env"; set +a
fi
: "${GONG_API:?GONG_API not found in env/api.env — cannot fetch weather/holiday}"

# ── 2. pull archive from VPS (incremental) ──────────────────────────────────
log "rsync archive from $VPS_HOST ($TRAIN_FROM..$TO)"
for tbl in bus_label bus_position bus_candidate; do
	mkdir -p "$DATA_DIR/$tbl"
	rsync -az "$VPS_HOST:$VPS_DATA/$tbl/" "$DATA_DIR/$tbl/" \
		|| { err "rsync $tbl failed"; exit 1; }
done

# available = local dates present for BOTH bus_label and bus_position, in range.
local_dates() {
	ls -1 "$DATA_DIR/$1" 2>/dev/null \
		| sed -n 's/^service_date=\([0-9-]\{10\}\)$/\1/p' \
		| awk -v f="$TRAIN_FROM" -v t="$TO" '$1>=f && $1<=t' | sort -u
}
AVAIL_DATES=()
while IFS= read -r d; do [ -n "$d" ] && AVAIL_DATES+=("$d"); done \
	< <(comm -12 <(local_dates bus_label) <(local_dates bus_position))
if [ "${#AVAIL_DATES[@]}" -eq 0 ]; then
	err "no bus_label∩bus_position dates in [$TRAIN_FROM..$TO] under $DATA_DIR"
	exit 1
fi
log "available dates (label∩position): ${#AVAIL_DATES[@]} ($TRAIN_FROM..$TO)"

# ── 3. sidecar fetch (local; GONG_API). best-effort like retrain. ───────────
log "holiday fetch ${FROM_YEAR}..${TO_YEAR}"
pyrun holiday_fetch.py --from-year "$FROM_YEAR" --to-year "$TO_YEAR" \
	|| log "holiday fetch failed (best-effort) — holiday features null"
log "weather fetch ${TRAIN_FROM}..${TO}"
pyrun weather_fetch.py --from "$TRAIN_FROM" --to "$TO" \
	|| log "weather fetch failed (best-effort) — weather features null"

# ── 4. dataset cleanup + build per available date ───────────────────────────
rm -rf "$DATA_DIR/dataset"; mkdir -p "$DATA_DIR/dataset"
for d in "${AVAIL_DATES[@]}"; do
	log "build_dataset $d"
	pyrun build_dataset.py "$d" --overwrite || { err "build_dataset failed $d"; exit 1; }
done

# ── 5. per-date route list (expected-routes gate input; same target filter as split.py) ─
mkdir -p "$DATA_DIR/tmp"
TARGET="$TARGET" pyrun - <<'PY' || { err "date-routes extraction failed"; exit 1; }
import polars as pl, glob, json, os
DATA_DIR=os.environ["DATA_DIR"]
TARGET_COLUMNS={'api':'api_target_seconds_to_arrival','label':'label_target_seconds_to_arrival','corrected':'corrected_target_seconds_to_arrival'}
col=TARGET_COLUMNS[os.environ["TARGET"]]
out={}
for d in sorted(glob.glob(f'{DATA_DIR}/dataset/service_date=*')):
    sd=d.split('service_date=')[-1]
    files=glob.glob(d+'/part-*.parquet')
    if not files: continue
    routes=(pl.scan_parquet(files)
            .filter(pl.col(col).is_not_null() & (pl.col(col)>=60) & (pl.col(col)<=2400))
            .select('route_id').unique().collect()['route_id'].to_list())
    out[sd]=sorted(str(r) for r in routes if r is not None)
open(f'{DATA_DIR}/tmp/date_routes.json','w').write(json.dumps(out))
PY

# ── 6. gate baseline: pull the deployed model (resolve relative symlink) ─────
PREV_REAL="$(ssh "$VPS_HOST" "readlink -f $VPS_DATA/model/current" 2>/dev/null || true)"
rm -rf "$DATA_DIR/prev"
if [ -n "$PREV_REAL" ]; then
	mkdir -p "$DATA_DIR/prev"
	rsync -az "$VPS_HOST:$PREV_REAL/" "$DATA_DIR/prev/" || { err "rsync prev model failed"; exit 1; }
fi
# gate.py silently skips regression if prev metrics/manifest are missing — make it explicit.
if [ "$ALLOW_NO_PREV" != "true" ]; then
	{ [ -f "$DATA_DIR/prev/metrics.json" ] && [ -f "$DATA_DIR/prev/training_manifest.json" ]; } \
		|| { err "prev model metrics/manifest missing — no regression baseline. Set ML_ALLOW_NO_PREV=true to bypass."; exit 1; }
fi

# ── 7. cumulative train ─────────────────────────────────────────────────────
DATE_CSV="$(IFS=,; echo "${AVAIL_DATES[*]}")"
TRAIN_ARGS=("$DATE_CSV" --target "$TARGET" --holdout-last-days-per-route "$HOLDOUT_DAYS" --sample-rows "$SAMPLE_ROWS")
[ "$TARGET_ENCODING" = "true" ] && TRAIN_ARGS+=(--target-encoding)
log "train dates=${#AVAIL_DATES[@]} holdout_days_per_route=$HOLDOUT_DAYS sample_rows=$SAMPLE_ROWS target_encoding=$TARGET_ENCODING"
pyrun train.py "${TRAIN_ARGS[@]}" || { err "train failed"; exit 1; }

RUN_DIR="$(ls -1dt "$DATA_DIR/experiments"/*/ 2>/dev/null | head -1)"; RUN_DIR="${RUN_DIR%/}"
RUN_ID="$(basename "$RUN_DIR")"
[ -f "$RUN_DIR/model.txt" ] || { err "no model.txt in $RUN_DIR"; exit 1; }
log "trained: $RUN_ID"

# ── 8. expected routes (union of date_routes) → gate ────────────────────────
pyrun - <<'PY' || { err "expected-routes extraction failed"; exit 1; }
import json, os
DATA_DIR=os.environ["DATA_DIR"]
dr=json.load(open(f'{DATA_DIR}/tmp/date_routes.json'))
routes=sorted({str(r) for v in dr.values() for r in v})
open(f'{DATA_DIR}/tmp/expected_routes.txt','w').write('\n'.join(routes))
PY

GATE_ARGS=(--model-dir "$DATA_DIR/experiments/$RUN_ID"
	--abs-mae "$ABS_MAE" --max-regression "$MAX_REGRESSION"
	--expected-routes-file "$DATA_DIR/tmp/expected_routes.txt")
[ -f "$DATA_DIR/prev/metrics.json" ] && GATE_ARGS+=(--prev-dir "$DATA_DIR/prev")

set +e
pyrun gate.py "${GATE_ARGS[@]}"
GATE_RC=$?
set -e
if [ "$GATE_RC" -eq 2 ]; then
	err "gate failed for $RUN_ID — not deployable"
	exit 1
elif [ "$GATE_RC" -ne 0 ]; then
	err "gate error rc=$GATE_RC"
	exit 1
fi

log "gate passed. deploy with: ml/ops/deploy_model.sh $RUN_ID"
echo "RUN_ID=$RUN_ID"
