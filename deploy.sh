#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="compose.yaml"
STATE_FILE=".env"
HEALTH_TIMEOUT_SECONDS=180

# 운영은 .env 의 COMPOSE_FILE=compose.yaml:compose.prod.yaml 로 override 를 합친다.
# 그 값이 있으면 -f 를 주지 않는다(-f 가 COMPOSE_FILE 을 이긴다 → compose.prod.yaml 이 빠진다).
compose() {
  if [ -n "$(state_get COMPOSE_FILE)" ]; then
    docker compose --env-file "${STATE_FILE}" "$@"
  else
    docker compose --env-file "${STATE_FILE}" -f "${COMPOSE_FILE}" "$@"
  fi
}

state_get() {
  local key="$1"
  awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "${STATE_FILE}" 2>/dev/null || true
}

state_set() {
  local key="$1"
  local value="$2"
  local temp_file
  temp_file=$(mktemp "${STATE_FILE}.XXXXXX")

  if [ -f "${STATE_FILE}" ]; then
    awk -F= -v key="${key}" -v value="${value}" '
      BEGIN { found = 0 }
      $1 == key { print key "=" value; found = 1; next }
      { print }
      END { if (!found) print key "=" value }
    ' "${STATE_FILE}" > "${temp_file}"
  else
    printf '%s=%s\n' "${key}" "${value}" > "${temp_file}"
  fi
  mv "${temp_file}" "${STATE_FILE}"
}

ensure_state() {
  touch "${STATE_FILE}"
  [ -n "$(state_get API_IMAGE)" ] || state_set API_IMAGE "nextroute:bootstrap"
  [ -n "$(state_get WORKER_IMAGE)" ] || state_set WORKER_IMAGE "nextroute:bootstrap"
  [ -n "$(state_get API_BUILD_DATE)" ] || state_set API_BUILD_DATE "bootstrap"
  [ -n "$(state_get WORKER_BUILD_DATE)" ] || state_set WORKER_BUILD_DATE "bootstrap"
  state_set PREVIOUS_API_IMAGE "$(state_get PREVIOUS_API_IMAGE)"
  state_set PREVIOUS_WORKER_IMAGE "$(state_get PREVIOUS_WORKER_IMAGE)"
  state_set PREVIOUS_API_BUILD_DATE "$(state_get PREVIOUS_API_BUILD_DATE)"
  state_set PREVIOUS_WORKER_BUILD_DATE "$(state_get PREVIOUS_WORKER_BUILD_DATE)"
}

new_image_tag() {
  local timestamp revision
  timestamp=$(date +%Y%m%d-%H%M%S)
  revision="${DEPLOY_REVISION:-}"
  if [ -z "${revision}" ]; then
    revision=$(git rev-parse --short HEAD 2>/dev/null || printf 'manual')
  fi
  printf 'nextroute:%s-%s' "${timestamp}" "${revision}"
}

build_image() {
  local image="$1"
  if [ ! -f app.jar ]; then
    echo "[deploy] app.jar is missing: copy the bootJar to /root/apps/nextroute/app.jar first." >&2
    exit 1
  fi
  echo "[deploy] building ${image}"
  docker build --tag "${image}" .
}

wait_healthy() {
  local service="$1"
  local deadline container_id status
  deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))

  while [ "${SECONDS}" -lt "${deadline}" ]; do
    container_id=$(compose ps -q "${service}")
    if [ -n "${container_id}" ]; then
      status=$(docker inspect --format '{{.State.Health.Status}}' "${container_id}" 2>/dev/null || true)
      if [ "${status}" = "healthy" ]; then
        echo "[deploy] ${service} is healthy"
        return 0
      fi
      if [ "${status}" = "unhealthy" ]; then
        echo "[deploy] ${service} became unhealthy" >&2
        return 1
      fi
    fi
    sleep 3
  done

  echo "[deploy] ${service} health check timed out" >&2
  return 1
}

set_role_image() {
  local role="$1"
  local image="$2"
  local build_date="$3"
  local upper current_image current_date
  upper=$(printf '%s' "${role}" | tr '[:lower:]' '[:upper:]')
  current_image=$(state_get "${upper}_IMAGE")
  current_date=$(state_get "${upper}_BUILD_DATE")

  state_set "PREVIOUS_${upper}_IMAGE" "${current_image}"
  state_set "PREVIOUS_${upper}_BUILD_DATE" "${current_date}"
  state_set "${upper}_IMAGE" "${image}"
  state_set "${upper}_BUILD_DATE" "${build_date}"
}

restore_role_state() {
  local role="$1"
  local upper current_image current_date previous_image previous_date
  upper=$(printf '%s' "${role}" | tr '[:lower:]' '[:upper:]')
  current_image=$(state_get "${upper}_IMAGE")
  current_date=$(state_get "${upper}_BUILD_DATE")
  previous_image=$(state_get "PREVIOUS_${upper}_IMAGE")
  previous_date=$(state_get "PREVIOUS_${upper}_BUILD_DATE")

  if [ -z "${previous_image}" ] || [ "${previous_image}" = "nextroute:bootstrap" ]; then
    echo "[deploy] no previous ${role} image is available" >&2
    return 1
  fi
  docker image inspect "${previous_image}" >/dev/null

  state_set "${upper}_IMAGE" "${previous_image}"
  state_set "${upper}_BUILD_DATE" "${previous_date}"
  state_set "PREVIOUS_${upper}_IMAGE" "${current_image}"
  state_set "PREVIOUS_${upper}_BUILD_DATE" "${current_date}"
}

has_service() {
  compose config --services 2>/dev/null | grep -qx "$1"
}

# worker 역할 = worker + (운영 override 에 있으면) worker2. 같은 WORKER_IMAGE 를 쓴다.
# 한 대씩 교체한다: worker 가 healthy 가 된 뒤 worker2 — 교체 중에도 worker 한 대는 늘 떠 있다(알림 유실 방지).
recreate_role() {
  local role="$1"
  compose up -d --no-deps --force-recreate "${role}"
  wait_healthy "${role}" || return 1
  if [ "${role}" = "worker" ] && has_service worker2; then
    compose up -d --no-deps --force-recreate worker2
    wait_healthy worker2
  fi
}

deploy_role() {
  local role="$1"
  local image build_date worker_before worker_after
  image=$(new_image_tag)
  build_date=${image#nextroute:}

  worker_before=""
  if [ "${role}" = "api" ]; then
    worker_before=$(compose ps -q worker 2>/dev/null || true)
  fi

  build_image "${image}"
  set_role_image "${role}" "${image}" "${build_date}"

  if ! recreate_role "${role}"; then
    echo "[deploy] ${role} failed; restoring the previous image" >&2
    restore_role_state "${role}"
    recreate_role "${role}"
    return 1
  fi

  if [ "${role}" = "api" ] && [ -n "${worker_before}" ]; then
    worker_after=$(compose ps -q worker 2>/dev/null || true)
    if [ "${worker_before}" != "${worker_after}" ]; then
      echo "[deploy] worker container changed during API deployment" >&2
      restore_role_state api
      recreate_role api
      return 1
    fi
  fi
}

deploy_all() {
  local image build_date legacy_running
  local old_api_image old_worker_image old_api_date old_worker_date
  local old_previous_api_image old_previous_worker_image
  local old_previous_api_date old_previous_worker_date

  old_api_image=$(state_get API_IMAGE)
  old_worker_image=$(state_get WORKER_IMAGE)
  old_api_date=$(state_get API_BUILD_DATE)
  old_worker_date=$(state_get WORKER_BUILD_DATE)
  old_previous_api_image=$(state_get PREVIOUS_API_IMAGE)
  old_previous_worker_image=$(state_get PREVIOUS_WORKER_IMAGE)
  old_previous_api_date=$(state_get PREVIOUS_API_BUILD_DATE)
  old_previous_worker_date=$(state_get PREVIOUS_WORKER_BUILD_DATE)

  image=$(new_image_tag)
  build_date=${image#nextroute:}
  build_image "${image}"

  set_role_image api "${image}" "${build_date}"
  set_role_image worker "${image}" "${build_date}"

  compose up -d postgres redis nextroute-ml-serve

  legacy_running=0
  if [ "$(docker inspect --format '{{.State.Running}}' nextroute-app-1 2>/dev/null || true)" = "true" ]; then
    legacy_running=1
    echo "[deploy] stopping legacy nextroute-app-1 for the initial role split"
    docker stop nextroute-app-1 >/dev/null
  fi

  if ! compose up -d --no-deps --force-recreate api worker \
      || ! wait_healthy api \
      || ! wait_healthy worker \
      || { has_service worker2 && ! recreate_worker2; }; then
    echo "[deploy] initial role split failed" >&2
    compose stop api worker || true

    state_set API_IMAGE "${old_api_image}"
    state_set WORKER_IMAGE "${old_worker_image}"
    state_set API_BUILD_DATE "${old_api_date}"
    state_set WORKER_BUILD_DATE "${old_worker_date}"
    state_set PREVIOUS_API_IMAGE "${old_previous_api_image}"
    state_set PREVIOUS_WORKER_IMAGE "${old_previous_worker_image}"
    state_set PREVIOUS_API_BUILD_DATE "${old_previous_api_date}"
    state_set PREVIOUS_WORKER_BUILD_DATE "${old_previous_worker_date}"

    if [ "${legacy_running}" -eq 1 ]; then
      docker start nextroute-app-1 >/dev/null
      echo "[deploy] legacy nextroute-app-1 restored"
    elif [ "${old_api_image}" != "nextroute:bootstrap" ] \
        && [ "${old_worker_image}" != "nextroute:bootstrap" ]; then
      echo "[deploy] restoring the previous API/Worker images"
      compose up -d --no-deps --force-recreate api worker
      wait_healthy api
      wait_healthy worker
      if has_service worker2; then recreate_worker2; fi
    fi
    return 1
  fi
}

recreate_worker2() {
  compose up -d --no-deps --force-recreate worker2
  wait_healthy worker2
}

rollback_role() {
  local role="$1"
  restore_role_state "${role}"
  recreate_role "${role}"
}

usage() {
  echo "Usage: $0 {api|worker|all|rollback-api|rollback-worker}" >&2
}

main() {
  local mode="${1:-api}"
  ensure_state

  case "${mode}" in
    api) deploy_role api ;;
    worker) deploy_role worker ;;
    all) deploy_all ;;
    rollback-api) rollback_role api ;;
    rollback-worker) rollback_role worker ;;
    *) usage; exit 2 ;;
  esac
}

main "$@"
