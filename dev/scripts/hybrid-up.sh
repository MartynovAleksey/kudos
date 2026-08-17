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
# Hybrid prod-mode test: the test CLUSTER + Vault run in Docker Compose, while
# the APPLICATION and its Vault Agent sidecar run in Kubernetes (Helm), exactly
# as in production. The app pod reaches the Docker services over host-published
# ports; *.test.local is mapped to the Docker host via pod hostAliases so the
# Kerberos service principals line up.
#
# Steps: build image -> bring up cluster+Vault in Docker -> create k8s
# ConfigMaps/Secret from the running stand -> helm install -> port-forward.
#
# Usage:  dev/scripts/hybrid-up.sh [--no-build] [--namespace kudos]
# Stop:   dev/scripts/hybrid-down.sh
#
set -euo pipefail

NS="kudos"
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --namespace) NS="$2"; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd -- "$script_dir/.." && pwd)"
cd "$root"
image="kudos:0.1.0"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"

dc() {
  env -u DOCKER_DEFAULT_PLATFORM DOCKER_CONFIG="$root/dev/docker/.docker-config" \
    "$compose_bin" -f "$root/compose.yaml" "$@"
}

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# --- 1. Build the application image (skip with --no-build) --------------------
if [ "$BUILD" -eq 1 ]; then
  step "Building application image $image"
  DOCKER_BUILDKIT=1 docker build -f dev/docker/app/Dockerfile -t "$image" .
else
  step "Skipping image build (--no-build); using existing $image"
fi

# --- 2. Bring up the Docker test environment (cluster + Vault) ----------------
# Not the app (it runs in k8s) and not the compose vault-agent (the k8s sidecar
# issues the pod's certificate).
step "Starting Docker test environment (this can take several minutes on a cold start)"
dc up -d freeipa kyuubi hdfs hbase ozone spark-history vault vault-bootstrap

step "Waiting for services to become healthy"
for svc in freeipa hdfs hbase ozone kyuubi vault; do
  cid="$(dc ps -q "$svc")"
  printf '    %-8s ' "$svc"
  for _ in $(seq 1 120); do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || echo none)"
    [ "$status" = healthy ] && break
    sleep 5
  done
  echo "$status"
  [ "$status" = healthy ] || { echo "service $svc not healthy; aborting" >&2; exit 1; }
done

# --- 3. Extract AppRole + config from the running stand -----------------------
step "Reading Vault AppRole credentials"
role_id="$(dc exec -T vault-agent true 2>/dev/null && dc exec -T vault-agent cat /vault/approle/role_id 2>/dev/null || \
  dc run --rm --no-deps -T -v kudos-test_vault-approle:/a --entrypoint sh vault -c 'cat /a/role_id')"
secret_id="$(dc run --rm --no-deps -T -v kudos-test_vault-approle:/a --entrypoint sh vault -c 'cat /a/secret_id')"
role_id="$(printf '%s' "$role_id" | tr -d '[:space:]')"
secret_id="$(printf '%s' "$secret_id" | tr -d '[:space:]')"
[ -n "$role_id" ] && [ -n "$secret_id" ] || { echo "failed to read AppRole creds" >&2; exit 1; }

step "Resolving the Docker host IP as seen from a pod"
kubectl run kudos-ipres --image=busybox:1.36 --restart=Never --command -- sh -c 'sleep 30' >/dev/null 2>&1 || true
kubectl wait --for=condition=Ready pod/kudos-ipres --timeout=60s >/dev/null 2>&1 || true
host_ip="$(kubectl exec kudos-ipres -- nslookup host.docker.internal 2>/dev/null | awk '/^Address/ && $NF ~ /^[0-9.]+$/ {ip=$NF} END{print ip}')"
kubectl delete pod kudos-ipres --force --grace-period=0 >/dev/null 2>&1 || true
host_ip="${host_ip:-192.168.65.254}"
echo "    host IP = $host_ip"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dc exec -T freeipa cat /shared/krb5.conf > "$work/krb5.conf"

cat > "$work/application.yml" <<'YAML'
spring:
  ldap:
    urls: ldap://freeipa.test.local:389
    base: dc=test,dc=local
    username: cn=Directory Manager
    password: DirectoryManager1
    user-dn-pattern: uid={0},cn=users,cn=accounts
kudos:
  cluster:
    webhdfs-url: http://hdfs.test.local:9870
    kyuubi-url: "jdbc:hive2://kyuubi.test.local:10009/default;principal=kyuubi/kyuubi.test.local@TEST.LOCAL"
    hbase-rest-url: http://hbase.test.local:8080
    ozone-ofs-uri: ofs://ozone.test.local/
    ozone-conf-dir: /etc/ozone/conf
    spark-history-url: http://sparkhistory.test.local:18080
    kerberos-principal: "{user}@TEST.LOCAL"
  features:
    editor: true
    files: true
    ozone: true
    hbase: true
    jobs: true
logging:
  level:
    com.kudos.ui: INFO
YAML

# --- 4. Create the k8s prerequisites the chart references ---------------------
step "Creating namespace, Secret and ConfigMaps in namespace '$NS'"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create secret generic kudos-vault-approle \
  --from-literal=role_id="$role_id" --from-literal=secret_id="$secret_id" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap kudos-krb5 --from-file=krb5.conf="$work/krb5.conf" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap kudos-cluster-conf \
  --from-file=core-site.xml=dev/docker/ozone/core-site.xml \
  --from-file=ozone-site.xml=dev/docker/ozone/ozone-site.xml \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap kudos-config --from-file=application.yml="$work/application.yml" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# --- 5. Deploy the chart ------------------------------------------------------
step "Installing the Helm release"
cat > "$work/values.yaml" <<YAML
image:
  repository: kudos
  tag: "0.1.0"
  pullPolicy: Never
vault:
  address: "http://host.docker.internal:8200"
  commonName: "app.test.local"
  ttl: "24h"
hostAliases:
  - ip: "$host_ip"
    hostnames:
      - freeipa.test.local
      - kyuubi.test.local
      - hdfs.test.local
      - hbase.test.local
      - ozone.test.local
      - sparkhistory.test.local
YAML
helm upgrade --install kudos "$root/deploy/helm/kudos" -n "$NS" -f "$work/values.yaml"

step "Waiting for the pod to become ready"
kubectl -n "$NS" rollout status deploy/kudos --timeout=180s

# --- 6. Port-forward ----------------------------------------------------------
step "Ready. Forwarding https://localhost:8443  (Ctrl-C to stop)"
cat <<EOF

  UI:     https://localhost:8443/    (login: admin / KudosAdmin2026Secure!)
  Note:   the certificate is issued by Vault's test CA, so the browser will warn
          about an untrusted issuer — accept and continue.

  Pods:   kubectl -n $NS get pods
  Logs:   kubectl -n $NS logs deploy/kudos -c app -f
  Stop:   dev/scripts/hybrid-down.sh

EOF
exec kubectl -n "$NS" port-forward svc/kudos 8443:8443 --address 127.0.0.1
