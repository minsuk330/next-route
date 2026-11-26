#!/usr/bin/env bash
# VPS 에서 실행: 배포 때마다 쌓이는 것 정리. 주 1회 cron.
#   0 4 * * 0 /root/apps/nextroute/ops/prod-prune.sh >> /var/log/nextroute-prune.log 2>&1
#
# - nextroute:* 이미지: .env 의 현재·직전(API/WORKER, 롤백용)만 남긴다
# - app.jar.bak-*: 최근 2개만 남긴다
# - docker build cache: 전부(다음 빌드가 조금 느려질 뿐)
# 2026-11-26 이미지 45개·jar 백업 23개·build cache 8.2G 가 쌓여 디스크 90% 였다.
# 앱 이미지 빌드가 CI(GHCR)로 옮겨지면 이 스크립트는 이미지 보존 개수만 맞춰 다시 본다.
#
#   --dry-run  지울 목록만 출력
set -euo pipefail

APP=/root/apps/nextroute
DRY="${1:-}"
cd "$APP"

run() { if [ "$DRY" = "--dry-run" ]; then echo "  (dry-run) $*"; else "$@"; fi; }

echo "=== $(date '+%F %T') prune 시작 $(df -h / | awk 'NR==2{print $5" 사용"}')"

keep=$(grep -E '^(PREVIOUS_)?(API|WORKER)_IMAGE=' .env | cut -d= -f2- | sort -u)
echo "남길 이미지:"; echo "$keep" | sed 's/^/  /'
if [ "$(echo "$keep" | grep -c '^nextroute:')" -lt 2 ]; then
  echo "  .env 에서 현재 이미지를 못 읽음 → 이미지 정리 건너뜀" >&2
  keep=""
fi

[ -n "$keep" ] && docker images --format '{{.Repository}}:{{.Tag}}' | grep '^nextroute:' | grep -vxF "$keep" | while read -r img; do
  # 실행 중 컨테이너가 쓰는 이미지는 docker 가 거부한다(-f 안 씀).
  run docker rmi "$img" >/dev/null 2>&1 && echo "  삭제 $img" || echo "  건너뜀 $img (사용 중?)"
done || true   # 지울 게 없으면 grep 이 1 → pipefail 로 여기서 끝나지 않게

ls -1t app.jar.bak-* 2>/dev/null | tail -n +3 | while read -r f; do
  run rm -f -- "$f"; echo "  삭제 $f"
done || true

run docker builder prune -af >/dev/null && echo "  build cache 정리"

echo "=== 끝 $(df -h / | awk 'NR==2{print $5" 사용"}')"
