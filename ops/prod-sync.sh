#!/usr/bin/env bash
# 운영(VPS) 설정 반영·드리프트 검사. 로컬(저장소 루트)에서 실행한다.
#
#   ops/prod-sync.sh diff [--quiet]   저장소 ↔ VPS 차이. --quiet 는 바뀐 파일 수만(종료코드 = 차이 있으면 1)
#   ops/prod-sync.sh apply            차이 있는 파일만 백업 후 전송. 컨테이너는 건드리지 않고 재생성 명령만 출력
#   ops/prod-sync.sh init             VPS .env 에 COMPOSE_FILE(compose.prod.yaml 합치기) 추가 — 최초 1회
#
# 원칙: VPS = 운영(최우선), 로컬 = 테스트. 아래 목록에 있는 파일만 보낸다(디렉터리 통째 rsync 금지).
# apply 는 origin/main 과 같은 커밋·깨끗한 작업 트리에서만 돈다(머지된 것만 운영에 간다).
set -euo pipefail

HOST="${PROD_HOST:-vps}"
APP=/root/apps/nextroute
OBS=/root/apps/observability

# 저장소 경로 → VPS 경로. 운영에 필요한 파일만.
FILES=(
  "compose.yaml|$APP/compose.yaml"
  "compose.prod.yaml|$APP/compose.prod.yaml"
  "deploy.sh|$APP/deploy.sh"
  "ops/prod-prune.sh|$APP/ops/prod-prune.sh"
  "ops/observability/compose.yaml|$OBS/compose.yaml"
  "ops/observability/compose.prod.yaml|$OBS/compose.prod.yaml"
  "ops/observability/prometheus/prometheus.yml|$OBS/prometheus/prometheus.yml"
  "ops/observability/loki/loki-config.yaml|$OBS/loki/loki-config.yaml"
  "ops/observability/alloy/config.alloy|$OBS/alloy/config.alloy"
  "ops/observability/tempo/tempo.yaml|$OBS/tempo/tempo.yaml"
)
# Grafana provisioning·대시보드는 디렉터리 안 파일 전부(저장소에 있는 것 기준).
while IFS= read -r f; do
  FILES+=("$f|$OBS/${f#ops/observability/}")
done < <(git ls-files 'ops/observability/grafana/provisioning/*' 'ops/observability/grafana/dashboards/*.json')

# 바뀐 파일 → 반영에 필요한 명령(자동 실행하지 않는다).
hint_for() {
  case "$1" in
    compose.yaml|compose.prod.yaml)
      echo "앱 compose 변경: 영향 서비스만 재생성. 예) ssh $HOST 'cd $APP && docker compose up -d --no-deps <service>'  (api·worker 는 ./deploy.sh 로)" ;;
    deploy.sh|ops/prod-prune.sh) echo "스크립트: 다음 실행부터 적용(재시작 없음)" ;;
    ops/observability/grafana/provisioning/alerting/*) echo "알림 규칙: ssh $HOST 'cd $OBS && docker compose restart grafana'" ;;
    ops/observability/grafana/dashboards/*) echo "대시보드: 30초 안에 자동 반영(재시작 불필요)" ;;
    ops/observability/grafana/*) echo "Grafana provisioning: ssh $HOST 'cd $OBS && docker compose restart grafana'" ;;
    ops/observability/prometheus/*) echo "Prometheus: ssh $HOST 'cd $OBS && docker compose restart prometheus'" ;;
    ops/observability/loki/*) echo "Loki: ssh $HOST 'cd $OBS && docker compose restart loki'" ;;
    ops/observability/alloy/*) echo "Alloy: ssh $HOST 'cd $OBS && docker compose restart alloy'" ;;
    ops/observability/compose*.yaml) echo "관측 compose: ssh $HOST 'cd $OBS && docker compose up -d'  (바뀐 서비스만 재생성됨)" ;;
    *) echo "확인 필요" ;;
  esac
}

cd "$(git rev-parse --show-toplevel)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fetch_remote() {
  local remotes=() pair
  for pair in "${FILES[@]}"; do remotes+=("${pair#*|}"); done
  # 없는 파일은 건너뛴다(새 파일). GNU tar(VPS).
  ssh "$HOST" "tar -C / --ignore-failed-read -cf - ${remotes[*]#/} 2>/dev/null" | tar -C "$TMP" -xf - 2>/dev/null || true
}

CHANGED=()
compute_changes() {
  local pair local_path remote_path
  for pair in "${FILES[@]}"; do
    local_path="${pair%%|*}"; remote_path="${pair#*|}"
    if [ ! -f "$TMP$remote_path" ] || ! cmp -s "$local_path" "$TMP$remote_path"; then
      CHANGED+=("$pair")
    fi
  done
}

cmd_diff() {
  local quiet="${1:-}" pair local_path remote_path
  fetch_remote
  compute_changes
  if [ "$quiet" = "--quiet" ]; then
    echo "${#CHANGED[@]}"
    [ "${#CHANGED[@]}" -eq 0 ]
    return
  fi
  for pair in ${CHANGED[@]+"${CHANGED[@]}"}; do
    local_path="${pair%%|*}"; remote_path="${pair#*|}"
    if [ -f "$TMP$remote_path" ]; then
      diff -u --label "vps:$remote_path" --label "repo:$local_path" "$TMP$remote_path" "$local_path" || true
    else
      echo "=== 새 파일: $local_path → vps:$remote_path"
    fi
  done
  echo "--- 차이 ${#CHANGED[@]}개 파일"
}

require_main() {
  git fetch -q origin main
  if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then
    echo "apply 는 origin/main 과 같은 커밋에서만 돈다(머지된 것만 운영에). 지금: $(git rev-parse --short HEAD)" >&2
    exit 1
  fi
  local pair
  for pair in "${FILES[@]}"; do
    if ! git diff --quiet HEAD -- "${pair%%|*}"; then
      echo "커밋 안 된 변경: ${pair%%|*}" >&2
      exit 1
    fi
  done
}

cmd_apply() {
  require_main
  fetch_remote
  compute_changes
  if [ "${#CHANGED[@]}" -eq 0 ]; then echo "차이 없음"; return; fi

  local ts pair local_path remote_path
  ts=$(date +%Y%m%d-%H%M%S)
  echo "[prod-sync] 백업: $APP-backups/sync-$ts, $OBS-backups/sync-$ts"
  for pair in "${CHANGED[@]}"; do
    remote_path="${pair#*|}"
    case "$remote_path" in $OBS/*) base="$OBS" ;; *) base="$APP" ;; esac
    ssh "$HOST" "if [ -f '$remote_path' ]; then d='$base-backups/sync-$ts/${remote_path#"$base"/}'; mkdir -p \"\$(dirname \"\$d\")\" && cp -p '$remote_path' \"\$d\"; fi"
  done

  for pair in "${CHANGED[@]}"; do
    local_path="${pair%%|*}"; remote_path="${pair#*|}"
    # 임시 파일에 쓴 뒤 mv — 중간 상태의 파일을 compose 가 읽지 않게.
    ssh "$HOST" "mkdir -p \"\$(dirname '$remote_path')\" && cat > '$remote_path.sync-tmp' && mv '$remote_path.sync-tmp' '$remote_path'" < "$local_path"
    if [ -x "$local_path" ]; then ssh "$HOST" "chmod +x '$remote_path'"; fi
    echo "[prod-sync] 전송 $local_path"
  done

  local dir
  for dir in "$APP" "$OBS"; do
    if ! ssh "$HOST" "grep -q '^COMPOSE_FILE=' '$dir/.env' 2>/dev/null"; then
      echo
      echo "⚠ $dir/.env 에 COMPOSE_FILE 없음 → compose.prod.yaml 이 무시된다. 지금 바로: ops/prod-sync.sh init" >&2
    fi
  done

  echo
  echo "반영 명령(직접 실행):"
  for pair in "${CHANGED[@]}"; do hint_for "${pair%%|*}"; done | sort -u | sed 's/^/  - /'
}

cmd_init() {
  local dir
  for dir in "$APP" "$OBS"; do
    ssh "$HOST" "cd '$dir' && touch .env && if grep -q '^COMPOSE_FILE=' .env; then echo '$dir: 이미 있음'; else cp -p .env .env.bak-\$(date +%Y%m%d-%H%M%S) && echo 'COMPOSE_FILE=compose.yaml:compose.prod.yaml' >> .env && echo '$dir: COMPOSE_FILE 추가'; fi"
  done
}

case "${1:-}" in
  diff) cmd_diff "${2:-}" ;;
  apply) cmd_apply ;;
  init) cmd_init ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
