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
# Applies working-tree changes to the running Kubernetes stand — the primary test
# environment. Rebuilds the images of the named components, retags them for the
# stand, refreshes the application's configuration and restarts the workloads.
# Everything else in the cluster is left alone, so this takes a minute or two
# instead of the twenty a full dev/scripts/k8s-up.sh costs.
#
# Usage:  dev/scripts/k8s-redeploy.sh [--namespace kudos-stand] [component ...]
#         components: app (default), kyuubi, hdfs, ozone, hbase, gravitino,
#                     trino, spark-history, log-collector
#
# Examples:
#   dev/scripts/k8s-redeploy.sh                 # Java/UI change
#   dev/scripts/k8s-redeploy.sh app kyuubi      # plus an engine or plugin change
#   dev/scripts/k8s-redeploy.sh --namespace other app
#
# A change to deploy/helm/** is not an image change: run helm upgrade (or
# dev/scripts/k8s-up.sh) for that.
#
set -euo pipefail

NS="kudos-stand"
components=()
while [ $# -gt 0 ]; do
  case "$1" in
    --namespace) NS="$2"; shift ;;
    -h | --help) sed -n '17,34p' "$0"; exit 0 ;;
    -*) echo "unknown arg: $1" >&2; exit 2 ;;
    *) components+=("$1") ;;
  esac
  shift
done
[ ${#components[@]} -gt 0 ] || components=(app)

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd -- "$script_dir/../.." && pwd)"
cd "$root"
tag="0.1.0"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }

kubectl -n "$NS" get deploy kudos >/dev/null 2>&1 \
  || die "No stand in namespace $NS. Bring it up first:  dev/scripts/k8s-up.sh"

# compose service -> the image it builds, the tag the stand runs it under, and
# the workload to restart. The application is the odd one out: its image is
# built from ./dist and the cluster runs it as plain kudos:<tag>.
stand_image() { # <component>
  case "$1" in
    app) echo "kudos:$tag" ;;
    *) echo "kudos-stand/$1:$tag" ;;
  esac
}
compose_image() { # <component>
  echo "kudos-test-$1:latest"
}
workload() { # <component>
  case "$1" in
    app) echo "kudos" ;;
    *) echo "$1" ;;
  esac
}

for component in "${components[@]}"; do
  case "$component" in
    app | kyuubi | hdfs | ozone | hbase | gravitino | trino | spark-history | log-collector) ;;
    flink*) die "Flink shares one image across three deployments; use dev/scripts/k8s-up.sh." ;;
    *) die "unknown component: $component" ;;
  esac
done

if printf '%s\n' "${components[@]}" | grep -qx app; then
  step "Building the application artifacts"
  bash "$script_dir/build-artifacts.sh"
fi

step "Building images: ${components[*]}"
docker compose build "${components[@]}"

step "Tagging them for the stand"
for component in "${components[@]}"; do
  docker tag "$(compose_image "$component")" "$(stand_image "$component")"
  echo "    $(compose_image "$component") -> $(stand_image "$component")"
done

if printf '%s\n' "${components[@]}" | grep -qx app; then
  # config/application.yml is mounted from this ConfigMap, so a settings change
  # reaches the cluster here rather than in an image layer.
  step "Refreshing the application configuration"
  kubectl -n "$NS" create configmap kudos-config \
    --from-file=application.yml=config/application.yml \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
fi

step "Restarting the workloads"
for component in "${components[@]}"; do
  kubectl -n "$NS" rollout restart "deploy/$(workload "$component")"
done
for component in "${components[@]}"; do
  kubectl -n "$NS" rollout status "deploy/$(workload "$component")" --timeout=10m
done

ui_port="$(kubectl -n "$NS" get svc kudos -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || true)"
step "Done"
[ -n "$ui_port" ] && echo "    UI: http://localhost:$ui_port/"
echo "    Logs: kubectl -n $NS logs deploy/kudos -f"
