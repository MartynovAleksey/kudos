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
# Removes the stand from Kubernetes and restores the cluster's stock CoreDNS
# configuration. Docker Compose, which still runs FreeIPA and Vault, is left
# alone. Safe to run twice.
#
# Usage: dev/scripts/k8s-down.sh [--namespace kudos-stand] [--keep-data]
#
set -euo pipefail

NS="kudos-stand"
KEEP_DATA=0
while [ $# -gt 0 ]; do
  case "$1" in
    --namespace) NS="$2"; shift ;;
    --keep-data) KEEP_DATA=1 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

kubectl version >/dev/null 2>&1 || { echo "No reachable cluster; nothing to remove."; exit 0; }

step "Removing the stand release"
helm -n "$NS" uninstall kudos-stand >/dev/null 2>&1 || true

if [ "$KEEP_DATA" -eq 0 ]; then
  step "Removing the stand's volumes"
  # Helm leaves a StatefulSet's volumeClaimTemplates behind by design.
  kubectl -n "$NS" delete pvc -l app.kubernetes.io/part-of=kudos-stand >/dev/null 2>&1 || true
  kubectl -n "$NS" delete pvc --all >/dev/null 2>&1 || true
else
  step "Keeping the stand's volumes (--keep-data)"
fi

step "Restoring the stock CoreDNS configuration"
# Strip the managed block rather than restoring a snapshot: a snapshot taken
# while the zone was already installed would put it back instead of removing it.
kubectl -n kube-system get cm coredns -o jsonpath='{.data.Corefile}' > /tmp/Corefile.current
if grep -q '^# BEGIN kudos-stand$' /tmp/Corefile.current; then
  awk '/^# BEGIN kudos-stand$/{skip=1} !skip{print} /^# END kudos-stand$/{skip=0}' \
    /tmp/Corefile.current > /tmp/Corefile.stock
  kubectl -n kube-system create cm coredns --from-file=Corefile=/tmp/Corefile.stock \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n kube-system rollout restart deploy/coredns >/dev/null
  kubectl -n kube-system rollout status deploy/coredns --timeout=180s >/dev/null
else
  echo "    zone not installed; CoreDNS left as it is"
fi

step "Removing the namespace"
kubectl delete namespace "$NS" --wait=false >/dev/null 2>&1 || true

echo
echo "Done. Docker Compose was not touched."
