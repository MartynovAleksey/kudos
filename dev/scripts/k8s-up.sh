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
# Brings the test cluster up in Kubernetes. FreeIPA and Vault keep running in
# Docker Compose: FreeIPA needs systemd with a host cgroup, and Vault is
# bootstrapped against it. Everything else runs in the cluster and reaches those
# two through the test.local zone this script installs into CoreDNS.
#
# Usage:  dev/scripts/k8s-up.sh [--no-build] [--namespace kudos-stand]
# Stop:   dev/scripts/k8s-down.sh
#
set -euo pipefail

NS="kudos-stand"
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
root="$(cd -- "$script_dir/../.." && pwd)"
cd "$root"
chart="deploy/helm/kudos-stand"
tag="0.1.0"
# Fixed NodePort for the KUDOS UI; see the application install below.
ui_node_port="30443"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }

# --- 1. The dependencies that stay in Docker Compose ---------------------------
# Refuse early and by name rather than leaving half a stand behind.
step "Checking the services that stay in Docker Compose"
for c in kudos-test-freeipa-1 kudos-test-vault-1; do
  docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null | grep -q true \
    || die "$c is not running. Start it first:  docker compose up -d freeipa vault"
done
docker exec kudos-test-freeipa-1 test -r /shared/krb5.conf \
  || die "FreeIPA has not issued the keytabs yet; wait for it to become healthy."

kubectl version >/dev/null 2>&1 \
  || die "No reachable Kubernetes cluster. Enable Kubernetes in Docker Desktop settings."

# --- 2. Images ----------------------------------------------------------------
# The cluster shares Docker Desktop's daemon with Compose, so the images the
# compose stand already built are usable as they are: retagging costs nothing and
# guarantees both ways of running the stand execute the same bits. `docker
# compose build` is only reached for an image that was never built.
step "Tagging stand images"
tag_from_compose() { # <compose image> <stand name> [<compose service to build>]
  if docker image inspect "$1" >/dev/null 2>&1; then
    docker tag "$1" "kudos-stand/$2:$tag"
  elif [ "$BUILD" -eq 1 ]; then
    echo "    $1 missing; building ${3:-$2}"
    docker compose build "${3:-$2}" >/dev/null
    docker tag "$1" "kudos-stand/$2:$tag"
  else
    die "$1 is missing and --no-build was given. Run without --no-build."
  fi
}
tag_from_compose kudos-test-hdfs:latest          hdfs           hdfs
tag_from_compose kudos-test-ozone:latest         ozone          ozone
tag_from_compose kudos-test-hbase:latest         hbase          hbase
tag_from_compose kudos-test-kyuubi:latest        kyuubi         kyuubi
tag_from_compose kudos-test-spark-history:latest spark-history  spark-history
tag_from_compose kudos-test-trino:latest         trino          trino
tag_from_compose kudos-test-trino-tls:latest     trino-tls      trino-tls
tag_from_compose kudos-test-gravitino:latest     gravitino      gravitino
tag_from_compose kudos-test-log-collector:latest log-collector  log-collector
tag_from_compose kudos-flink:latest              flink          flink-jobmanager

# --- 3. Namespace and the DNS zone -------------------------------------------
step "Creating namespace $NS"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

step "Resolving the Docker host address from inside the cluster"
kubectl run kudos-hostprobe --image=busybox:1.36 --restart=Never \
  --command -- sh -c 'sleep 30' >/dev/null 2>&1 || true
kubectl wait --for=condition=Ready pod/kudos-hostprobe --timeout=90s >/dev/null 2>&1
host_ip="$(kubectl exec kudos-hostprobe -- nslookup host.docker.internal 2>/dev/null \
  | awk '/^Address/ && $NF ~ /^[0-9.]+$/ {ip=$NF} END{print ip}')"
kubectl delete pod kudos-hostprobe --force --grace-period=0 >/dev/null 2>&1 || true
[ -n "$host_ip" ] || die "Could not resolve host.docker.internal from the cluster."
echo "    docker host: $host_ip"

step "Installing the test.local zone into CoreDNS"
# Idempotent by construction: whatever is in the Corefile now, everything between
# the markers is dropped before the zone is appended again. Appending blindly
# would define the same zone twice, and CoreDNS refuses to start on that.
kubectl -n kube-system get cm coredns -o jsonpath='{.data.Corefile}' > /tmp/Corefile.current
awk '/^# BEGIN kudos-stand$/{skip=1} !skip{print} /^# END kudos-stand$/{skip=0}' \
  /tmp/Corefile.current > /tmp/Corefile.stock
sed -e "s/__HOST_IP__/$host_ip/g" -e "s/__NAMESPACE__/$NS/g" \
  "$chart/files/coredns-test-local.conf.tmpl" | grep -v '^\s*#' > /tmp/zone.conf
{ cat /tmp/Corefile.stock; echo "# BEGIN kudos-stand"; cat /tmp/zone.conf; echo "# END kudos-stand"; } > /tmp/Corefile.new
kubectl -n kube-system create cm coredns --from-file=Corefile=/tmp/Corefile.new \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n kube-system rollout restart deploy/coredns >/dev/null
kubectl -n kube-system rollout status deploy/coredns --timeout=180s >/dev/null

# --- 4. Keytabs and configuration ---------------------------------------------
# One source of truth: the keytabs come from the running FreeIPA, the XML and
# YAML from the same files the compose stand mounts. Nothing is duplicated into
# the chart.
step "Copying the keytabs issued by FreeIPA into a Secret"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
docker exec kudos-test-freeipa-1 sh -c 'cd /shared && tar cf - .' | tar xf - -C "$work"
# s3-credentials.env is minted by Ozone at runtime and, in the cluster, written
# into the shared volume by the Ozone pod. Seeding a stale copy from here would
# overwrite the live one every time any pod restarts.
rm -f "$work/s3-credentials.env"
# Kerberos talks UDP first. Reaching the KDC from a pod means crossing Docker
# Desktop's gateway, where UDP is unreliable, and the client hangs in KdcComm
# instead of falling back. TCP is available (port 88 answers), so make the client
# prefer it. Only the copy handed to the cluster is touched; the compose stand
# keeps the file FreeIPA issued.
if ! grep -q udp_preference_limit "$work/krb5.conf"; then
  # No leading whitespace: the JDK's krb5 parser is stricter than MIT's and
  # rejects the file outright, which surfaces as "krb5.conf loading failed".
  python3 - "$work/krb5.conf" <<'PYEOF'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
p.write_text(p.read_text().replace("[libdefaults]", "[libdefaults]\nudp_preference_limit = 1", 1))
PYEOF
fi
kubectl -n "$NS" create secret generic kudos-stand-kerberos \
  --from-file="$work" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

step "Publishing service configuration as ConfigMaps"
# Hadoop, Ozone and HBase write their principals as <service>/_HOST@REALM and
# expand _HOST from the local canonical hostname. In compose that resolves
# because each container sets `hostname: <name>.test.local`; a pod's hostname is
# always a cluster.local name, and no amount of DNS makes it otherwise. So the
# same XML the compose stand mounts is copied with _HOST replaced by the concrete
# name. Same source files, substituted on the way in — not a second copy to keep
# in step.
conf_for() { # <service> <config dir> <files...>
  svc="$1"; dir="$2"; shift 2
  args=""
  for f in "$@"; do
    sed "s/_HOST/$svc.test.local/g" "$dir/$f" > "$work/$f"
    args="$args --from-file=$f=$work/$f"
  done
  # shellcheck disable=SC2086
  kubectl -n "$NS" create configmap "kudos-stand-$svc-conf" $args \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

# The addresses in hdfs-site.xml name the service itself, which in compose is the
# container's own address. In the cluster that name resolves to the Service IP,
# which no pod can bind to, so the listeners never come up. Hadoop's *-bind-host
# settings exist for exactly this: listen on every interface, keep advertising the
# configured name. servicerpc is deliberately absent — giving it a bind-host with
# no matching address makes the DataNode dial null:0.
conf_for ozone dev/docker/ozone core-site.xml ozone-site.xml
conf_for hbase dev/docker/hbase core-site.xml hbase-site.xml
# HBase manages its own ZooKeeper and refuses to start unless it finds its own
# name in hbase.zookeeper.quorum. It compares names, not addresses, and a pod's
# hostname cannot contain dots, so `hbase.test.local` never matches. Only the
# HBase pod reads this file, so pointing the quorum at the pod's own short name
# is contained: every client still reaches it as hbase.test.local through the
# Service.
python3 - "$work/hbase-site.xml" <<'PYEOF'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
p.write_text(p.read_text().replace(
    "<value>hbase.test.local</value>", "<value>hbase</value>", 1))
PYEOF
kubectl -n "$NS" create configmap kudos-stand-hbase-conf \
  --from-file=core-site.xml="$work/core-site.xml" \
  --from-file=hbase-site.xml="$work/hbase-site.xml" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# hdfs is built by hand so the bind hosts land in the same ConfigMap as the rest:
# a merge patch afterwards would replace the whole data map and drop core-site.xml.
sed "s/_HOST/hdfs.test.local/g" dev/docker/hdfs/core-site.xml > "$work/core-site.xml"
sed "s/_HOST/hdfs.test.local/g" dev/docker/hdfs/hdfs-site.xml > "$work/hdfs-site.raw"
python3 - "$work/hdfs-site.raw" "$work/hdfs-site.xml" <<'PYEOF'
import sys
src, dst = sys.argv[1], sys.argv[2]
extra = "".join(
    "  <property><name>%s</name><value>0.0.0.0</value></property>\n" % k
    for k in ("dfs.namenode.rpc-bind-host", "dfs.namenode.http-bind-host",
              "dfs.namenode.https-bind-host", "dfs.datanode.bind-host"))
open(dst, "w").write(open(src).read().replace("</configuration>", extra + "</configuration>"))
PYEOF
kubectl -n "$NS" create configmap kudos-stand-hdfs-conf \
  --from-file=core-site.xml="$work/core-site.xml" \
  --from-file=hdfs-site.xml="$work/hdfs-site.xml" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap kudos-stand-app-config \
  --from-file=application.yml=config/application.yml \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap kudos-stand-starrocks-bootstrap \
  --from-file=bootstrap.sh=dev/docker/starrocks/bootstrap.sh \
  --from-file=bootstrap.sql=dev/docker/starrocks/bootstrap.sql \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# The StarRocks service-account password is issued by Vault, which stays in
# compose. Copy what its Agent already rendered rather than minting a second
# credential the application would not know about.
step "Copying the StarRocks password issued by Vault"
docker exec kudos-test-vault-agent-1 cat /vault/secrets/starrocks-password > "$work/starrocks-password"
[ -s "$work/starrocks-password" ] || die "Vault Agent has not rendered the StarRocks password yet."
kubectl -n "$NS" create secret generic kudos-stand-starrocks-password \
  --from-file=starrocks-password="$work/starrocks-password" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# Trino serves TLS with a certificate Vault issued, and the application trusts it
# through the Vault CA. Copy what the compose Agent rendered rather than minting a
# self-signed certificate nothing else would trust.
step "Copying the Trino certificate issued by Vault"
docker exec kudos-test-vault-agent-1 cat /vault/secrets/trino.pem > "$work/trino.pem"
docker exec kudos-test-vault-agent-1 cat /vault/secrets/ca.crt   > "$work/ca.crt"
[ -s "$work/trino.pem" ] || die "Vault Agent has not rendered the Trino certificate yet."
kubectl -n "$NS" create secret generic kudos-stand-trino-tls \
  --from-file=trino.pem="$work/trino.pem" --from-file=ca.crt="$work/ca.crt" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# --- 5. The stand itself ------------------------------------------------------
step "Installing the stand"
node_ip="$(kubectl get node -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
helm upgrade --install kudos-stand "$chart" \
  --namespace "$NS" \
  --set external.hostAddress="$host_ip" \
  --set nodeAddress="$node_ip" \
  --set image.tag="$tag" \
  --wait --timeout 20m

# --- 6. The application ------------------------------------------------------
# Reuses deploy/helm/kudos unchanged. config/application.yml already addresses
# every service as <name>.test.local, which is exactly what CoreDNS resolves
# inside the cluster, so the same file serves both ways of running the stand.
step "Installing the application"
docker image inspect kudos-test-app:latest >/dev/null 2>&1 \
  && docker tag kudos-test-app:latest kudos:0.1.0
kubectl -n "$NS" create configmap kudos-krb5 --from-file=krb5.conf="$work/krb5.conf" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap kudos-cluster-conf \
  --from-file=core-site.xml=dev/docker/ozone/core-site.xml \
  --from-file=ozone-site.xml=dev/docker/ozone/ozone-site.xml \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" create configmap kudos-config --from-file=application.yml=config/application.yml \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# The Vault AppRole only matters for the TLS profile, which this stand leaves off
# (plain HTTP on 8443, as compose documents); an empty Secret satisfies the chart.
kubectl -n "$NS" create secret generic kudos-vault-approle \
  --from-literal=role_id="" --from-literal=secret_id="" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# NodePort, not a port-forward: the stand is the primary test environment, so its
# UI has to answer at one fixed address that survives restarts and needs no
# terminal held open. Docker Desktop publishes a NodePort on localhost.
helm upgrade --install kudos deploy/helm/kudos \
  --namespace "$NS" --set image.tag=0.1.0 --set vault.enabled=false \
  --set service.type=NodePort --set service.nodePort="$ui_node_port" \
  --set podSecurityContext.runAsUser=65532 --set podSecurityContext.fsGroup=65532 \
  --wait --timeout 10m || true

step "Ready"
cat <<EOF

  UI:       http://localhost:$ui_node_port/
  Pods:     kubectl -n $NS get pods
  Logs:     kubectl -n $NS logs <pod> -f
  Redeploy: dev/scripts/k8s-redeploy.sh [app|kyuubi|...]
  Stop:     dev/scripts/k8s-down.sh --namespace $NS

EOF
