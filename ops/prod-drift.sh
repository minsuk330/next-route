#!/usr/bin/env bash
# 운영 드리프트 검사: 저장소와 다른 VPS 파일 수를 출력한다. 0 이면 종료코드 0.
# 주간 요약이 이 숫자를 쓴다. 자세한 diff 는 ops/prod-sync.sh diff.
set -euo pipefail
exec "$(dirname "$0")/prod-sync.sh" diff --quiet
