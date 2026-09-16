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

#
# Tears down the hybrid prod-mode test started by dev/scripts/hybrid-up.sh:
# uninstalls the Helm release and stops the Docker test environment.
#
# Usage:  dev/scripts/hybrid-down.sh [--namespace kudos] [--purge]
#   --purge  also delete the namespace and remove Docker volumes (data loss).
#
set -euo pipefail

NS="kudos"
PURGE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --namespace) NS="$2"; shift ;;
    --purge) PURGE=1 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd -- "$script_dir/../.." && pwd)"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"
dc() {
  env -u DOCKER_DEFAULT_PLATFORM DOCKER_CONFIG="$root/dev/docker/.docker-config" \
    "$compose_bin" -f "$root/compose.yaml" "$@"
}

echo "==> Stopping any port-forward"
pkill -f "port-forward svc/kudos" 2>/dev/null || true

echo "==> Uninstalling Helm release"
helm uninstall kudos -n "$NS" 2>/dev/null || true

if [ "$PURGE" -eq 1 ]; then
  echo "==> Deleting namespace $NS"
  kubectl delete namespace "$NS" --wait=false 2>/dev/null || true
  echo "==> Stopping Docker env and removing volumes"
  dc down -v
else
  echo "==> Stopping Docker env (volumes kept)"
  dc stop
fi

echo "Done."
