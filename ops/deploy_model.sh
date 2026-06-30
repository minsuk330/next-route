#!/usr/bin/env bash
# Deploy a locally-trained experiment to the VPS (Mac → VPS). Uploads the
# experiment, swaps model/current, recreates serve, verifies, rolls back on fail.
# Run ml/ops/train_local.sh first; pass its RUN_ID here.
#
# Usage:
#   deploy_model.sh <run_id>                                  # model-only (schema1, no image change)
#   deploy_model.sh --with-image --image <tag> <run_id>       # schema-change (D2): pin serve image too
#
# --with-image pins ML_SERVE_IMAGE (a sha tag) in the VPS compose .env so the serve
# image and the locally-trained model move together (same commit). On failure both
# the .env image and the model symlink roll back. The serve image in compose must be
# templated as `image: ${ML_SERVE_IMAGE:-...:latest}` (repo compose.yaml + VPS override).
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

# ── arg parse ───────────────────────────────────────────────────────────────
WITH_IMAGE=false
IMAGE_TAG=""
RUN_ID=""
while [ $# -gt 0 ]; do
	case "$1" in
		--with-image) WITH_IMAGE=true; shift ;;
		--image) IMAGE_TAG="${2:-}"; shift 2 ;;
		*) RUN_ID="$1"; shift ;;
	esac
done
if [ "$WITH_IMAGE" = "true" ] && [ -z "$IMAGE_TAG" ]; then
	err "--with-image requires --image <ghcr.io/...:sha-xxxxxxx>"; exit 1
fi

# ── 1. resolve run_id (arg or newest local experiment) ──────────────────────
if [ -z "$RUN_ID" ]; then
	newest="$(ls -1dt "$EXP_DIR"/*/ 2>/dev/null | head -1)"; newest="${newest%/}"
	RUN_ID="$(basename "$newest" 2>/dev/null || true)"
fi
[ -n "$RUN_ID" ] || { err "no run_id given and no local experiment found"; exit 1; }
[ -f "$EXP_DIR/$RUN_ID/model.txt" ] || { err "missing $EXP_DIR/$RUN_ID/model.txt"; exit 1; }
log "deploying $RUN_ID (with_image=$WITH_IMAGE ${IMAGE_TAG:+image=$IMAGE_TAG})"

# ── 2. precheck: serve must read /data/model/current, else symlink swap is moot ─
MP="$(ssh "$VPS_HOST" "cd $APP_DIR && docker compose config" 2>/dev/null | grep -E 'ML_MODEL_PATH' | head -1 || true)"
echo "$MP" | grep -q "/data/model/current" \
	|| { err "serve ML_MODEL_PATH is not /data/model/current (got: ${MP:-none}). Point serve at the symlink first."; exit 1; }

# ── 3. upload experiment ────────────────────────────────────────────────────
rsync -az "$EXP_DIR/$RUN_ID/" "$VPS_HOST:$VPS_DATA/experiments/$RUN_ID/" \
	|| { err "rsync experiment failed"; exit 1; }

# ── 4. remote: (optional image pin) + symlink swap + recreate + verify + rollback ─
ssh "$VPS_HOST" bash -s <<REMOTE
set -euo pipefail
cd "$APP_DIR"
MODEL_DIR="$VPS_DATA/model"
ENV_FILE="$APP_DIR/.env"
WITH_IMAGE="$WITH_IMAGE"
IMAGE_TAG="$IMAGE_TAG"
mkdir -p "\$MODEL_DIR"

PREV_SYMLINK="\$(readlink "\$MODEL_DIR/current" 2>/dev/null || true)"
PREV_IMG_LINE="\$(grep -E '^ML_SERVE_IMAGE=' "\$ENV_FILE" 2>/dev/null || true)"

set_serve_image() {  # \$1 = tag
	touch "\$ENV_FILE"
	if grep -q '^ML_SERVE_IMAGE=' "\$ENV_FILE"; then
		sed -i "s|^ML_SERVE_IMAGE=.*|ML_SERVE_IMAGE=\$1|" "\$ENV_FILE"
	else
		echo "ML_SERVE_IMAGE=\$1" >> "\$ENV_FILE"
	fi
}
restore_serve_image() {  # revert .env to prior state (persistent)
	sed -i '/^ML_SERVE_IMAGE=/d' "\$ENV_FILE" 2>/dev/null || true
	[ -n "\$PREV_IMG_LINE" ] && echo "\$PREV_IMG_LINE" >> "\$ENV_FILE"
}

if [ "\$WITH_IMAGE" = "true" ]; then
	set_serve_image "\$IMAGE_TAG"
	docker compose pull $SERVE_SVC
fi

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
	echo "[deploy-remote] OK model_version=\$ver image=\${IMAGE_TAG:-unchanged}"
else
	echo "[deploy-remote] FAIL ok=\$ok ver=\$ver expected=$RUN_ID — rolling back" >&2
	[ "\$WITH_IMAGE" = "true" ] && restore_serve_image
	if [ -n "\$PREV_SYMLINK" ]; then
		ln -sfn "\$PREV_SYMLINK" "\$MODEL_DIR/current.tmp"
		mv -T "\$MODEL_DIR/current.tmp" "\$MODEL_DIR/current"
	fi
	docker compose up -d --force-recreate $SERVE_SVC || true
	exit 1
fi
REMOTE

log "deployed $RUN_ID"
