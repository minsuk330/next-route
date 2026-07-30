#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RESULT_DIR="${SCRIPT_DIR}/results"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.1.0}"
BASE_URL="${BASE_URL:-https://api.refit-100.site}"

RATE="${1:?Usage: $0 RATE DURATION [LABEL]}"
DURATION="${2:?Usage: $0 RATE DURATION [LABEL]}"
LABEL="${3:-read-api}"

case "${RATE}" in
  ''|*[!0-9]*|0)
    echo "RATE must be a positive integer: ${RATE}" >&2
    exit 2
    ;;
esac

if [[ ! "${DURATION}" =~ ^[1-9][0-9]*(s|m|h)$ ]]; then
  echo "DURATION must look like 30s, 10m, or 1h: ${DURATION}" >&2
  exit 2
fi

mkdir -p "${RESULT_DIR}"

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
SAFE_LABEL=$(printf '%s' "${LABEL}" | tr -cs '[:alnum:]_.-' '-')
RUN_ID="${TIMESTAMP}-${SAFE_LABEL}-rps${RATE}-${DURATION}"
SUMMARY_FILE="${RUN_ID}.summary.json"
CONSOLE_FILE="${RUN_ID}.console.log"
META_FILE="${RUN_ID}.meta.txt"

{
  printf 'run_id=%s\n' "${RUN_ID}"
  printf 'started_at_utc=%s\n' "${TIMESTAMP}"
  printf 'base_url=%s\n' "${BASE_URL}"
  printf 'rate_rps=%s\n' "${RATE}"
  printf 'duration=%s\n' "${DURATION}"
  printf 'k6_image=%s\n' "${K6_IMAGE}"
  printf 'git_sha=%s\n' "$(git -C "${SCRIPT_DIR}/../.." rev-parse HEAD)"
} > "${RESULT_DIR}/${META_FILE}"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "${SCRIPT_DIR}:/scripts:ro" \
  --volume "${RESULT_DIR}:/results" \
  --env "BASE_URL=${BASE_URL}" \
  --env "RATE=${RATE}" \
  --env "DURATION=${DURATION}" \
  --env "RUN_ID=${RUN_ID}" \
  "${K6_IMAGE}" run \
  --summary-export "/results/${SUMMARY_FILE}" \
  /scripts/read-api.js \
  | tee "${RESULT_DIR}/${CONSOLE_FILE}"

printf 'summary=%s\n' "${RESULT_DIR}/${SUMMARY_FILE}"
printf 'console=%s\n' "${RESULT_DIR}/${CONSOLE_FILE}"
printf 'metadata=%s\n' "${RESULT_DIR}/${META_FILE}"
