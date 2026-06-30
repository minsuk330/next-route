#!/usr/bin/env bash
# Deploy a locally-trained experiment to the VPS (Mac → VPS). Uploads the
# experiment, swaps model/current, recreates serve, verifies, rolls back on fail.
# Run ml/ops/train_local.sh first; pass its RUN_ID here.
#
# D1 scope: schema1 model deploy only. The serve image is kept as-is (no pull) —
# a schema2 (D2) rollout also needs image rollback and is handled separately.
set -euo pipefail

ts() { date +"%Y-%m-%dT%H:%M:%S%z"; }
log() { echo "[deploy] $(ts) $*"; }
err() { echo "[error] $(ts) $*" >&2; }

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EXP_DIR="$REPO_ROOT/ml/data/experiments"
VPS_HOST="${ML_VPS_HOST:-vps}"
VPS_DATA="${ML_VPS_DATA:-/srv/nextroute/ml-data}"
APP_DIR="${ML_APP_DIR:-/root/apps/nextroute}"
SERVE_SVC="${ML_SERVE_CONTAINER:-nextroute-ml-serve}"

# ── 1. resolve run_id (arg or newest local experiment) ──────────────────────
RUN_ID="${1:-}"
if [ -z "$RUN_ID" ]; then
	newest="$(ls -1dt "$EXP_DIR"/*/ 2>/dev/null | head -1)"; newest="${newest%/}"
	RUN_ID="$(basename "$newest" 2>/dev/null || true)"
fi
[ -n "$RUN_ID" ] || { err "no run_id given and no local experiment found"; exit 1; }
[ -f "$EXP_DIR/$RUN_ID/model.txt" ] || { err "missing $EXP_DIR/$RUN_ID/model.txt"; exit 1; }
log "deploying $RUN_ID"

# ── 2. precheck: serve must read /data/model/current, else symlink swap is moot ─
MP="$(ssh "$VPS_HOST" "cd $APP_DIR && docker compose config" 2>/dev/null | grep -E 'ML_MODEL_PATH' | head -1 || true)"
echo "$MP" | grep -q "/data/model/current" \
	|| { err "serve ML_MODEL_PATH is not /data/model/current (got: ${MP:-none}). Point serve at the symlink first."; exit 1; }

# ── 3. upload experiment ────────────────────────────────────────────────────
rsync -az "$EXP_DIR/$RUN_ID/" "$VPS_HOST:$VPS_DATA/experiments/$RUN_ID/" \
	|| { err "rsync experiment failed"; exit 1; }

# ── 4. swap symlink + recreate serve (no image pull) + verify + rollback ────
ssh "$VPS_HOST" bash -s <<REMOTE
set -euo pipefail
cd "$APP_DIR"
MODEL_DIR="$VPS_DATA/model"
mkdir -p "\$MODEL_DIR"
PREV="\$(readlink "\$MODEL_DIR/current" 2>/dev/null || true)"

ln -sfn "../experiments/$RUN_ID" "\$MODEL_DIR/current.tmp"
mv -T "\$MODEL_DIR/current.tmp" "\$MODEL_DIR/current"
docker compose up -d --force-recreate $SERVE_SVC

ok=0
for _ in \$(seq 1 30); do
	if docker compose exec -T $SERVE_SVC python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8001/health').status==200 else 1)" 2>/dev/null; then ok=1; break; fi
	sleep 2
done
ver="\$(docker compose exec -T $SERVE_SVC python -c "import urllib.request,json; print(json.load(urllib.request.urlopen('http://localhost:8001/metadata'))['model_version'])" 2>/dev/null || true)"

if [ "\$ok" = "1" ] && [ "\$ver" = "$RUN_ID" ]; then
	echo "[deploy-remote] OK model_version=\$ver"
else
	echo "[deploy-remote] FAIL ok=\$ok ver=\$ver expected=$RUN_ID — rolling back to \${PREV:-none}" >&2
	if [ -n "\$PREV" ]; then
		ln -sfn "\$PREV" "\$MODEL_DIR/current.tmp"
		mv -T "\$MODEL_DIR/current.tmp" "\$MODEL_DIR/current"
		docker compose up -d --force-recreate $SERVE_SVC || true
	fi
	exit 1
fi
REMOTE

log "deployed $RUN_ID"
