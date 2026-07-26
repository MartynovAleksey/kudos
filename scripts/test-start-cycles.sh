#!/usr/bin/env bash
# Copyright 2026 Aleksey Martynov and contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Repeatedly verifies that the already-built test environment can be restarted.
# It deliberately preserves named volumes: each iteration tests a real restart,
# including FreeIPA's persisted realm and service keytabs.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
docker_bin="$(command -v docker)"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"
cycles="${1:-10}"

if ! [[ "$cycles" =~ ^[1-9][0-9]*$ ]]; then
  echo "Usage: $0 [positive-cycle-count]" >&2
  exit 2
fi

run_docker() {
  env DOCKER_CONFIG="$project_root/docker/.docker-config" PATH="/usr/bin:/bin" "$docker_bin" "$@"
}

run_compose() {
  env \
    -u DOCKER_DEFAULT_PLATFORM \
    DOCKER_CONFIG="$project_root/docker/.docker-config" \
    PATH="/usr/bin:/bin" \
    "$compose_bin" \
    -f "$project_root/compose.yaml" \
    "$@"
}

log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

# Always address published ports by IP. Resolving "localhost" goes through the
# macOS resolver, which under heavy container load can block far longer than
# curl's --max-time and turn a bounded wait into a stalled one.
probe_http() {
  curl \
    --fail \
    --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time "$1" \
    "${@:2}"
}

wait_healthy() {
  local service="$1"
  local container
  local status
  local deadline

  container="$(run_compose ps -q "$service")"
  if [[ -z "$container" ]]; then
    echo "Missing container: $service" >&2
    return 1
  fi

  deadline=$((SECONDS + 360))
  while (( SECONDS < deadline )); do
    status="$(
      run_docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container"
    )"
    case "$status" in
      healthy | running)
        return 0
        ;;
      unhealthy | exited | dead)
        run_docker logs --tail 120 "$container" >&2 || true
        return 1
        ;;
    esac
    sleep 3
  done
  run_docker logs --tail 120 "$container" >&2 || true
  echo "Timed out waiting for $service" >&2
  return 1
}

dump_endpoint_diagnostics() {
  local service="$1"
  local container

  echo "--- diagnostics for $service ---" >&2
  container="$(run_compose ps -q "$service" || true)"
  if [[ -n "$container" ]]; then
    run_docker inspect \
      --format 'state={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restarts={{.RestartCount}}' \
      "$container" >&2 || true
    run_docker port "$container" >&2 || true
    run_docker logs --tail 60 "$container" >&2 || true
  fi
}

# The application speaks TLS with a certificate issued by the realm's CA, so
# every probe verifies against that CA instead of skipping the check.
app_ca="$(mktemp)"
app_resolve=()
trap 'rm -f "$app_ca"' EXIT

refresh_app_ca() {
  local freeipa
  freeipa="$(run_compose ps -q freeipa)"
  if [[ -n "$freeipa" ]] && run_docker exec "$freeipa" cat /shared/ca.crt >"$app_ca" 2>/dev/null; then
    app_resolve=(--resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca")
    return 0
  fi
  return 1
}

verify_endpoints() {
  # All ports are exercised from the host to catch published-port and DNS regressions.
  local deadline=$((SECONDS + 180))
  local app_ok=0
  local hue_ok=0
  local app_health
  local hue_page

  while (( SECONDS < deadline )); do
    if (( ! app_ok )) \
        && app_health="$(probe_http 10 "${app_resolve[@]}" \
              https://app.test.local:8443/actuator/health 2>/dev/null)" \
        && grep -q '"status"' <<<"$app_health"; then
      app_ok=1
      log "Spring API answered /actuator/health"
    fi
    if (( ! hue_ok )) \
        && hue_page="$(probe_http 15 --location http://127.0.0.1:8082/ 2>/dev/null)" \
        && grep -qi 'hue' <<<"$hue_page"; then
      hue_ok=1
      log "Docker Hub Hue reference rendered its start page"
    fi
    (( app_ok && hue_ok )) && break
    sleep 3
  done
  if (( ! app_ok )); then
    log "Spring API did not become healthy" >&2
    dump_endpoint_diagnostics app
    return 1
  fi
  if (( ! hue_ok )); then
    log "Docker Hub Hue reference did not render its start page" >&2
    dump_endpoint_diagnostics hue-reference
    return 1
  fi
  for port in 8443 10009 10099 9870 9090 9862 18080; do
    if ! bash -c ": >/dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      log "Published port $port is not reachable from the host" >&2
      return 1
    fi
  done
}

for cycle in $(seq 1 "$cycles"); do
  log "=== restart verification $cycle/$cycles ==="
  run_compose down --remove-orphans
  run_compose up -d --no-build --remove-orphans
  for service in freeipa kyuubi hdfs hbase ozone spark-history app; do
    wait_healthy "$service"
    log "$service is ready"
  done
  # FreeIPA republishes the CA on every start, so refresh it each cycle rather
  # than trusting a copy taken before the realm came back up.
  if ! refresh_app_ca; then
    log "Could not read the realm CA needed to verify the application's TLS" >&2
    exit 1
  fi
  verify_endpoints
  DOCKER_COMPOSE_BIN="$compose_bin" "$script_dir/test-kerberos-services.sh"
  log "=== restart verification $cycle/$cycles passed ==="
done

log "All $cycles restart verification cycles passed."
