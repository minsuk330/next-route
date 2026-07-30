#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RESULT_DIR="${SCRIPT_DIR}/results"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.1.0}"
BASE_URL="${BASE_URL:-https://api.refit-100.site}"
STOP_ID="${STOP_ID:-101000013}"
ROUTE_ID="${ROUTE_ID:-100100044}"

MODE="${1:?Usage: $0 stampede|ttl [LABEL]}"
LABEL="${2:-portfolio-${MODE}}"

case "${MODE}" in
  stampede|ttl) ;;
  *)
    echo "MODE must be stampede or ttl: ${MODE}" >&2
    exit 2
    ;;
esac

mkdir -p "${RESULT_DIR}"

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
SAFE_LABEL=$(printf '%s' "${LABEL}" | tr -cs '[:alnum:]_.-' '-')
RUN_ID="${TIMESTAMP}-${SAFE_LABEL}-${MODE}"
SUMMARY_FILE="${RUN_ID}.summary.json"
CONSOLE_FILE="${RUN_ID}.console.log"
META_FILE="${RUN_ID}.meta.txt"

{
  printf 'run_id=%s\n' "${RUN_ID}"
  printf 'started_at_utc=%s\n' "${TIMESTAMP}"
  printf 'base_url=%s\n' "${BASE_URL}"
  printf 'mode=%s\n' "${MODE}"
  printf 'stop_id=%s\n' "${STOP_ID}"
  printf 'route_id=%s\n' "${ROUTE_ID}"
  printf 'k6_image=%s\n' "${K6_IMAGE}"
  printf 'git_sha=%s\n' "$(git -C "${SCRIPT_DIR}/../.." rev-parse HEAD)"
} > "${RESULT_DIR}/${META_FILE}"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "${SCRIPT_DIR}:/scripts:ro" \
  --volume "${RESULT_DIR}:/results" \
  --env "BASE_URL=${BASE_URL}" \
  --env "MODE=${MODE}" \
  --env "RUN_ID=${RUN_ID}" \
  --env "STOP_ID=${STOP_ID}" \
  --env "ROUTE_ID=${ROUTE_ID}" \
  "${K6_IMAGE}" run \
  --summary-export "/results/${SUMMARY_FILE}" \
  /scripts/portfolio-bus-cache.js \
  | tee "${RESULT_DIR}/${CONSOLE_FILE}"

printf 'summary=%s\n' "${RESULT_DIR}/${SUMMARY_FILE}"
printf 'console=%s\n' "${RESULT_DIR}/${CONSOLE_FILE}"
printf 'metadata=%s\n' "${RESULT_DIR}/${META_FILE}"
