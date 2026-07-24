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

# Performs data-path checks, not just TCP probes. Every operation authenticates
# as the disposable FreeIPA user admin and fails the script on the first error.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
docker_bin="$(command -v docker)"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"
admin_password="${TEST_ADMIN_PASSWORD:-K8SparkAdmin2026Secure!}"

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

container_id() {
  local id
  id="$(run_compose ps -q "$1")"
  if [[ -z "$id" ]]; then
    echo "Missing container: $1" >&2
    return 1
  fi
  printf '%s\n' "$id"
}

freeipa_id="$(container_id freeipa)"
hdfs_id="$(container_id hdfs)"
kyuubi_id="$(container_id kyuubi)"
ozone_id="$(container_id ozone)"
hbase_id="$(container_id hbase)"

run_docker exec -e TEST_ADMIN_PASSWORD="$admin_password" "$freeipa_id" bash -lc '
  set -euo pipefail
  printf "%s\n" "$TEST_ADMIN_PASSWORD" | kinit admin@TEST.LOCAL
  klist -s
  ldapwhoami -x -H ldap://freeipa.test.local \
    -D uid=admin,cn=users,cn=accounts,dc=test,dc=local \
    -w "$TEST_ADMIN_PASSWORD" | grep -q "uid=admin"
'
echo "PASS FreeIPA LDAP + Kerberos password login"

hdfs_output="$(run_docker exec "$hdfs_id" bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  printf "hdfs-kerberos-ok\n" >/tmp/k8spark-hdfs-in
  base=http://hdfs.test.local:9870/webhdfs/v1
  curl --fail --silent --negotiate -u : -X PUT "$base/user/admin?op=MKDIRS" >/dev/null
  curl --fail --silent --location-trusted --negotiate -u : -X PUT \
    --upload-file /tmp/k8spark-hdfs-in \
    "$base/user/admin/k8spark-hdfs-test?op=CREATE&overwrite=true" >/dev/null
  curl --fail --silent --location-trusted --negotiate -u : \
    "$base/user/admin/k8spark-hdfs-test?op=OPEN"
  curl --fail --silent --negotiate -u : -X DELETE \
    "$base/user/admin/k8spark-hdfs-test?op=DELETE" >/dev/null
')"
grep -q '^hdfs-kerberos-ok$' <<<"$hdfs_output"
echo "PASS HDFS WebHDFS SPNEGO put/get"

sql_output="$(run_docker exec "$kyuubi_id" bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  jdbc_url="jdbc:hive2://kyuubi.test.local:10009/default;"
  jdbc_url+="principal=kyuubi/kyuubi.test.local@TEST.LOCAL"
  /opt/kyuubi/bin/beeline \
    -u "$jdbc_url" \
    --silent=true \
    --showHeader=false \
    --outputformat=csv2 \
    -e "SELECT 40 + 2 AS result"
')"
grep -q '^42$' <<<"$sql_output"
echo "PASS Kyuubi Kerberos JDBC SQL"

ozone_output="$(run_docker exec "$ozone_id" bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export OZONE_CONF_DIR=/opt/hadoop/etc/hadoop HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  export OZONE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  ozone sh volume create /k8sparktest --user=admin 2>/dev/null || true
  ozone sh bucket create /k8sparktest/files 2>/dev/null || true
  printf "ozone-kerberos-ok\n" >/tmp/k8spark-ozone-in
  rm -f /tmp/k8spark-ozone-out
  ozone sh key put /k8sparktest/files/test-key /tmp/k8spark-ozone-in
  ozone sh key get /k8sparktest/files/test-key /tmp/k8spark-ozone-out
  cmp /tmp/k8spark-ozone-in /tmp/k8spark-ozone-out
  cat /tmp/k8spark-ozone-out
  ozone sh key delete /k8sparktest/files/test-key
')"
grep -q '^ozone-kerberos-ok$' <<<"$ozone_output"
echo "PASS Ozone Kerberos put/get"

hbase_output="$(run_docker exec "$hbase_id" bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export HBASE_HOME=/opt/hbase
  export HBASE_CONF_DIR=/opt/hbase/conf
  export HBASE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  printf "disable '\''k8spark_test'\''\ndrop '\''k8spark_test'\''\n" \
    | /opt/hbase/bin/hbase shell -n >/dev/null 2>&1 || true
  {
    printf "create \047k8spark_test\047, \047d\047\n"
    printf "put \047k8spark_test\047, \047row1\047, \047d:value\047, \047hbase-kerberos-ok\047\n"
    printf "get \047k8spark_test\047, \047row1\047\n"
    printf "disable \047k8spark_test\047\n"
    printf "drop \047k8spark_test\047\n"
  } | /opt/hbase/bin/hbase shell -n
')"
grep -q 'value=hbase-kerberos-ok' <<<"$hbase_output"
echo "PASS HBase Kerberos RPC put/get"

# Published ports are addressed by IP: resolving "localhost" goes through the
# macOS resolver, which under heavy container load can block far longer than
# curl's --max-time and stall an otherwise bounded check.
app_health="$(curl --fail --silent --connect-timeout 5 --max-time 15 \
  http://127.0.0.1:8081/actuator/health)"
grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$app_health"
hue_page="$(curl --fail --silent --location --connect-timeout 5 --max-time 30 \
  http://127.0.0.1:8082/)"
grep -qi 'hue' <<<"$hue_page"
echo "PASS Spring health + Docker Hub Hue reference"
echo "All Kerberos functional checks passed."
