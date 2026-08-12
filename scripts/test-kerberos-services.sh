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
admin_password="${TEST_ADMIN_PASSWORD:-KudosAdmin2026Secure!}"
analyst_password="${TEST_ANALYST_PASSWORD:-KudosAnalyst2026Secure!}"
starrocks_password="${TEST_STARROCKS_PASSWORD:-KudosStarRocks2026}"

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
# The application is served over TLS by a certificate Vault's PKI engine issued
# and the vault-agent sidecar published. Verifying against Vault's CA, rather
# than passing --insecure, is what makes the checks below evidence that TLS is
# actually configured correctly.
vault_agent_id="$(container_id vault-agent)"
app_ca="$(mktemp)"
trap 'rm -f "$app_ca"' EXIT
run_docker exec "$vault_agent_id" cat /vault/secrets/ca.crt >"$app_ca"
app_base="https://app.test.local:8443"
# The certificate names app.test.local; published ports are on the loopback.
app_resolve=(--resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca")
kyuubi_id="$(container_id kyuubi)"
ozone_id="$(container_id ozone)"
hbase_id="$(container_id hbase)"
starrocks_id="$(container_id starrocks)"

run_docker exec -e TEST_ADMIN_PASSWORD="$admin_password" "$freeipa_id" bash -lc '
  set -euo pipefail
  printf "%s\n" "$TEST_ADMIN_PASSWORD" | kinit admin@TEST.LOCAL
  klist -s
  ldapwhoami -x -H ldap://freeipa.test.local \
    -D uid=admin,cn=users,cn=accounts,dc=test,dc=local \
    -w "$TEST_ADMIN_PASSWORD" | grep -q "uid=admin"
'
echo "PASS FreeIPA LDAP + Kerberos password login"

run_docker exec -e TEST_ANALYST_PASSWORD="$analyst_password" "$freeipa_id" bash -lc '
  set -euo pipefail
  printf "%s\n" "$TEST_ANALYST_PASSWORD" | kinit analyst@TEST.LOCAL
  klist -s
  ldapwhoami -x -H ldap://freeipa.test.local \
    -D uid=analyst,cn=users,cn=accounts,dc=test,dc=local \
    -w "$TEST_ANALYST_PASSWORD" | grep -q "uid=analyst"
  echo "${IPA_TEST_PASSWORD}" | kinit admin@TEST.LOCAL
  ipa group-show kudos-administrators | grep -q "admin"
'
echo "PASS FreeIPA analyst user role identity"

hdfs_output="$(run_docker exec "$hdfs_id" bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  printf "hdfs-kerberos-ok\n" >/tmp/kudos-hdfs-in
  base=http://hdfs.test.local:9870/webhdfs/v1
  curl --fail --silent --negotiate -u : -X PUT "$base/user/admin?op=MKDIRS" >/dev/null
  curl --fail --silent --location-trusted --negotiate -u : -X PUT \
    --upload-file /tmp/kudos-hdfs-in \
    "$base/user/admin/kudos-hdfs-test?op=CREATE&overwrite=true" >/dev/null
  curl --fail --silent --location-trusted --negotiate -u : \
    "$base/user/admin/kudos-hdfs-test?op=OPEN"
  curl --fail --silent --negotiate -u : -X DELETE \
    "$base/user/admin/kudos-hdfs-test?op=DELETE" >/dev/null
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
  ozone sh volume create /kudostest --user=admin 2>/dev/null || true
  ozone sh bucket create /kudostest/files 2>/dev/null || true
  printf "ozone-kerberos-ok\n" >/tmp/kudos-ozone-in
  rm -f /tmp/kudos-ozone-out
  ozone sh key put /kudostest/files/test-key /tmp/kudos-ozone-in
  ozone sh key get /kudostest/files/test-key /tmp/kudos-ozone-out
  cmp /tmp/kudos-ozone-in /tmp/kudos-ozone-out
  cat /tmp/kudos-ozone-out
  ozone sh key delete /kudostest/files/test-key
')"
grep -q '^ozone-kerberos-ok$' <<<"$ozone_output"
echo "PASS Ozone Kerberos put/get"

hbase_output="$(run_docker exec "$hbase_id" bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  base=http://hbase.test.local:8080
  table=kudos_test
  curl --silent --negotiate -u : -X DELETE "$base/$table/schema" >/dev/null 2>&1 || true
  curl --fail --silent --negotiate -u : -H "Content-Type: application/json" \
    -X PUT "$base/$table/schema" \
    -d "{\"name\":\"$table\",\"ColumnSchema\":[{\"name\":\"d\"}]}" >/dev/null
  curl --fail --silent --negotiate -u : -H "Content-Type: application/json" \
    -X PUT "$base/$table/row1" \
    -d "{\"Row\":[{\"key\":\"cm93MQ==\",\"Cell\":[{\"column\":\"ZDp2YWx1ZQ==\",\"\$\":\"aGJhc2UtcmVzdC1rZXJiZXJvcy1vaw==\"}]}]}" >/dev/null
  curl --fail --silent --negotiate -u : "$base/$table/row1"
  curl --fail --silent --negotiate -u : -X DELETE "$base/$table/schema" >/dev/null
')"
grep -q 'aGJhc2UtcmVzdC1rZXJiZXJvcy1vaw==' <<<"$hbase_output"
echo "PASS HBase REST SPNEGO put/get"

hbase_anonymous_status="$(run_docker exec "$hbase_id" curl --silent --output /dev/null \
  --write-out '%{http_code}' --connect-timeout 5 --max-time 20 http://hbase.test.local:8080/)"
if [[ "$hbase_anonymous_status" != "401" && "$hbase_anonymous_status" != "403" ]]; then
  echo "HBase REST Gateway answered $hbase_anonymous_status instead of refusing anonymous access" >&2
  exit 1
fi
echo "PASS HBase REST Gateway requires SPNEGO"

starrocks_output="$(run_docker exec -e STARROCKS_PASSWORD="$starrocks_password" "$starrocks_id" bash -lc '
  mysql -h starrocks.test.local -P 9030 -ukudos_svc -p"$STARROCKS_PASSWORD" \
    --batch --skip-column-names -e "SELECT 40 + 2"
')"
grep -q '^42$' <<<"$starrocks_output"
echo "PASS StarRocks MySQL service account SQL"

# Published ports are addressed by IP: resolving "localhost" goes through the
# macOS resolver, which under heavy container load can block far longer than
# curl's --max-time and stall an otherwise bounded check.
app_health="$(curl --fail --silent --connect-timeout 5 --max-time 15 \
  "${app_resolve[@]}" "$app_base/actuator/health")"
grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$app_health"
hue_page="$(curl --fail --silent --location --connect-timeout 5 --max-time 30 \
  http://127.0.0.1:8082/)"
grep -qi 'hue' <<<"$hue_page"
echo "PASS Spring health + Docker Hub Hue reference"

# Exercise every storage path through the application itself, not just through
# the service containers. A dependency missing from the app image fails only
# here: the container CLIs carry their own copies and stay green.
app_api() {
  curl --fail --silent --connect-timeout 5 --max-time 60 \
    "${app_resolve[@]}" -u "admin:$admin_password" "$@"
}

# A regular user must authenticate successfully, but must never browse the
# shared Spark History root or request another owner's jobs with `user=admin`.
analyst_ui_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --connect-timeout 5 --max-time 30 "${app_resolve[@]}" \
  -u "analyst:$analyst_password" "$app_base/spark-ui/")"
if [[ "$analyst_ui_status" != 403 ]]; then
  echo "Regular user unexpectedly accessed shared Spark UI: HTTP $analyst_ui_status" >&2
  exit 1
fi
analyst_jobs="$(curl --fail --silent --connect-timeout 5 --max-time 60 \
  "${app_resolve[@]}" -u "analyst:$analyst_password" \
  "$app_base/api/spark/applications?limit=50&user=admin")"
if grep -q '"user":"admin"' <<<"$analyst_jobs"; then
  echo "Regular user received another owner's Spark jobs" >&2
  exit 1
fi
echo "PASS Spring role isolation (analyst)"

app_api --get --data-urlencode 'path=/' $app_base/api/hdfs/list \
  | grep -q '\['
echo "PASS Spring API HDFS listing"

app_api --get --data-urlencode 'path=/' $app_base/api/ozone/list \
  | grep -q '\['
echo "PASS Spring API Ozone listing"

app_api $app_base/api/hbase/tables | grep -q '\['
echo "PASS Spring API HBase tables"

app_api -H 'Content-Type: application/json' -X POST "$app_base/api/sql/execute" \
  -d '{"engine":"trino","sql":"SELECT count(*) FROM nation"}' \
  | grep -q '"rows":\[\[25\]\]'
echo "PASS Spring API Trino SQL as logged-in Kerberos user"

app_api "$app_base/api/trino/history" \
  | grep -q 'SELECT count(\*) FROM nation'
echo "PASS Spring API Trino query history"

app_api -H 'Content-Type: application/json' -X POST "$app_base/api/sql/execute" \
  -d '{"engine":"starrocks","sql":"SELECT 40 + 2 AS result"}' \
  | grep -q '"rows":\[\[42\]\]'
echo "PASS Spring API StarRocks SQL through Vault service account"

# Full HBase browser lifecycle, the way Hue's HBase app drives it: create a
# table with column families, write and read a row, alter a family, then drop
# it. A regression in any of these operations fails the run.
hb_table="k8s_selftest_$$"
hb_json=(-H 'Content-Type: application/json')
app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/table/delete" \
  -d "{\"table\":\"$hb_table\"}" >/dev/null 2>&1 || true
app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/table/create" \
  -d "{\"table\":\"$hb_table\",\"families\":[{\"name\":\"cf\",\"maxVersions\":3}]}" >/dev/null
app_api "$app_base/api/hbase/tables" | grep -q "\"name\":\"$hb_table\""
app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/row" \
  -d "{\"table\":\"$hb_table\",\"row\":\"r1\",\"cells\":{\"cf:a\":\"hello\"}}" >/dev/null
app_api "$app_base/api/hbase/scan?table=$hb_table&limit=10" \
  | grep -q '"column":"cf:a","value":"hello"'
app_api "$app_base/api/hbase/regions?table=$hb_table" | grep -q '"name":'

app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/family/add" \
  -d "{\"table\":\"$hb_table\",\"family\":{\"name\":\"cf2\",\"maxVersions\":2}}" >/dev/null
app_api "$app_base/api/hbase/describe?table=$hb_table" | grep -q '"name":"cf2"'

# Verify the complete mutation path: multiple versions, a binary cell, column
# deletion, and CSV bulk upload. Temporary files are unnecessary: curl reads
# the multipart body directly from stdin.
app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/row" \
  -d "{\"table\":\"$hb_table\",\"row\":\"versions\",\"cells\":{\"cf:a\":\"old\"}}" >/dev/null
sleep 1
app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/row" \
  -d "{\"table\":\"$hb_table\",\"row\":\"versions\",\"cells\":{\"cf:a\":\"new\"}}" >/dev/null
versions="$(app_api "$app_base/api/hbase/cell/versions?table=$hb_table&row=versions&column=cf:a&versions=10")"
grep -q '"value":"old"' <<<"$versions"
grep -q '"value":"new"' <<<"$versions"

printf '\000binary\377' \
  | app_api -X POST -F 'file=@-;filename=value.bin;type=application/octet-stream' \
      "$app_base/api/hbase/cell/upload?table=$hb_table&row=binary&column=cf:a" >/dev/null
app_api "$app_base/api/hbase/row?table=$hb_table&row=binary" | grep -q '"binary":true'

app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/row" \
  -d "{\"table\":\"$hb_table\",\"row\":\"delete-cell\",\"cells\":{\"cf:a\":\"keep\",\"cf:remove\":\"remove\"}}" >/dev/null
app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/cell/delete" \
  -d "{\"table\":\"$hb_table\",\"row\":\"delete-cell\",\"columns\":[\"cf:remove\"]}" >/dev/null
if app_api "$app_base/api/hbase/row?table=$hb_table&row=delete-cell" | grep -q '"column":"cf:remove"'; then
  echo "HBase cell delete did not remove the requested column" >&2
  exit 1
fi

bulk_result="$(printf 'row,cf:a\ncsv-row,csv-value\n' \
  | app_api -X POST -F 'file=@-;filename=rows.csv;type=text/csv' \
      "$app_base/api/hbase/bulk?table=$hb_table")"
[[ "$bulk_result" == "1" ]]
app_api "$app_base/api/hbase/row?table=$hb_table&row=csv-row" \
  | grep -q '"value":"csv-value"'

# Cursor pagination, the way the row browser pages forward: a page ends on a
# row key, and the next page starts exclusively after it — no repeats, no gaps.
for k in row_a row_b row_c; do
  app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/row" \
    -d "{\"table\":\"$hb_table\",\"row\":\"$k\",\"cells\":{\"cf:a\":\"$k\"}}" >/dev/null
done
page2="$(app_api "$app_base/api/hbase/scan?table=$hb_table&start=row_b&startInclusive=false&limit=10")"
if ! grep -q '"rowKey":"row_c"' <<<"$page2" || grep -q '"rowKey":"row_b"' <<<"$page2"; then
  echo "HBase cursor pagination repeated or skipped a row" >&2
  exit 1
fi
echo "PASS HBase cursor pagination (start-exclusive next page)"

app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/family/modify" \
  -d "{\"table\":\"$hb_table\",\"family\":{\"name\":\"cf\",\"maxVersions\":7}}" >/dev/null
app_api "$app_base/api/hbase/describe?table=$hb_table" | grep -q '"name":"cf","maxVersions":7'
app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/row/delete" \
  -d "{\"table\":\"$hb_table\",\"row\":\"r1\"}" >/dev/null

app_api "${hb_json[@]}" -X POST "$app_base/api/hbase/table/delete" \
  -d "{\"table\":\"$hb_table\"}" >/dev/null
if app_api "$app_base/api/hbase/tables" | grep -q "\"name\":\"$hb_table\""; then
  echo "HBase self-test table $hb_table was not dropped" >&2
  exit 1
fi
echo "PASS HBase browser lifecycle (schema, regions, mutations, versions, bulk, drop)"

app_api -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT 40 + 2 AS result"}' \
  $app_base/api/sql/execute \
  | grep -q '42'
echo "PASS Spring API Kyuubi SQL"

# The SQL above started a Spark engine, so its event log must have reached
# Ozone and, once the history server picks it up, the jobs endpoint.
run_docker exec "$ozone_id" bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export OZONE_CONF_DIR=/opt/hadoop/etc/hadoop HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
  export OZONE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    if ozone sh key list /spark/eventlogs 2>/dev/null | grep -q "\"name\""; then
      exit 0
    fi
    sleep 5
  done
  echo "No Spark event log appeared in ofs://ozone.test.local/spark/eventlogs" >&2
  exit 1
'
echo "PASS Spark event log written to Ozone"

deadline=$((SECONDS + 180))
spark_jobs_ok=0
while (( SECONDS < deadline )); do
  if app_api "$app_base/api/spark/applications?limit=10" \
      | grep -q '"id"'; then
    spark_jobs_ok=1
    break
  fi
  sleep 5
done
if (( ! spark_jobs_ok )); then
  echo "Spark History returned no applications through the app API" >&2
  exit 1
fi
echo "PASS Spring API Spark History applications"

# The jobs screen drills into a run through this application's proxy, so the
# Spark UI and its assets have to come back from the application, not 18080.
#
# Only a completed application is usable: the history server serves a run's
# pages after it has replayed the event log, and the engine this script just
# started is still being written when the list first reports it. The list comes
# back newest first, so take the oldest entry — that one is certainly replayed.
spark_app_id=""
proxied_page=""
proxy_status=""
deadline=$((SECONDS + 300))
while (( SECONDS < deadline )); do
  spark_app_id="$(
    app_api "$app_base/api/spark/applications?limit=20" \
      | tr '}' '\n' \
      | grep '"completed":true' \
      | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' \
      | tail -1
  )"
  if [[ -n "$spark_app_id" ]]; then
    proxy_url="$app_base/spark-ui/history/$spark_app_id/jobs/"
    # The first request for a run makes the history server replay its event
    # log, which takes far longer than serving the page afterwards.
    proxy_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
      --connect-timeout 5 --max-time 120 "${app_resolve[@]}" \
      -u "admin:$admin_password" "$proxy_url")"
    if [[ "$proxy_status" == "200" ]]; then
      proxied_page="$(curl --fail --silent --connect-timeout 5 --max-time 120 \
        "${app_resolve[@]}" -u "admin:$admin_password" "$proxy_url")"
      [[ -n "$proxied_page" ]] && break
    fi
  fi
  proxied_page=""
  sleep 5
done
if [[ -z "$proxied_page" ]]; then
  echo "The proxied Spark UI never became available for a completed run" >&2
  echo "  application: ${spark_app_id:-<none found>}" >&2
  echo "  last proxy status: ${proxy_status:-<no request made>}" >&2
  exit 1
fi

# Spark renders its links against X-Forwarded-Context; unprefixed links would
# send the browser straight to the history server and out of this application.
grep -q 'href="/spark-ui/' <<<"$proxied_page"
echo "PASS Spark UI proxied through the app"

# Every asset the page pulls has to survive the proxy too. A body that does not
# match its declared length shows up here and nowhere else.
while read -r asset; do
  [[ -z "$asset" ]] && continue
  app_api --output /dev/null "$app_base$asset"
done < <(grep -oE '(href|src)="/spark-ui/static/[^"]*"' <<<"$proxied_page" \
  | sed 's/.*="//;s/"$//' | sort -u)
echo "PASS Spark UI assets proxied through the app"

logs_headers="$(app_api --output /dev/null --dump-header - \
  "$app_base/spark-ui/api/v1/applications/$spark_app_id/logs")"
grep -qi 'content-disposition:.*attachment' <<<"$logs_headers"
echo "PASS Spark event log download through the app"

# The UI shell has to render for a signed-in browser session as well.
curl --fail --silent --connect-timeout 5 --max-time 15 \
  "${app_resolve[@]}" "$app_base/login" \
  | grep -q 'Sign In'
echo "PASS Spring UI login page"

# A browser signs in once with the form and then reaches /api on the session
# cookie alone. A regression here re-challenges with Basic, and the browser
# pops a second, native login box on the first data fetch.
session_jar="$(mktemp)"
trap 'rm -f "$app_ca" "$session_jar"' EXIT
login_csrf="$(curl --fail --silent "${app_resolve[@]}" -c "$session_jar" "$app_base/login" \
  | grep -oE 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"$//')"
curl --fail --silent --output /dev/null "${app_resolve[@]}" -b "$session_jar" -c "$session_jar" \
  --data-urlencode "username=admin" \
  --data-urlencode "password=$admin_password" \
  --data-urlencode "_csrf=$login_csrf" \
  "$app_base/login"
session_headers="$(curl --silent --output /dev/null --dump-header - \
  "${app_resolve[@]}" -b "$session_jar" "$app_base/api/hdfs/list?path=/")"
if ! grep -qE '^HTTP/[0-9.]+ 200' <<<"$session_headers" \
  || grep -qi '^www-authenticate:' <<<"$session_headers"; then
  echo "A form-login session did not authenticate /api without a Basic challenge" >&2
  printf '%s\n' "$session_headers" >&2
  exit 1
fi
echo "PASS API served on the form-login session, no second Basic prompt"

# The signed-in page carries the ticket countdown, and signing out (a plain GET,
# as the sidebar link and the countdown both use) ends the session.
curl --fail --silent "${app_resolve[@]}" -b "$session_jar" "$app_base/editor" \
  | grep -q 'id="k8s-ticket-timer"'
logout_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "${app_resolve[@]}" -b "$session_jar" -c "$session_jar" \
  -H 'Accept: text/html' "$app_base/logout")"
after_logout="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "${app_resolve[@]}" -b "$session_jar" "$app_base/editor")"
if [[ "$logout_status" != "302" || "$after_logout" != "302" ]]; then
  echo "Sign out did not end the session (logout=$logout_status, after=$after_logout)" >&2
  exit 1
fi
echo "PASS ticket countdown shown and sign out ends the session"

# Kyuubi serves its web UI under /ui and redirects the root there.
curl --fail --silent --location --output /dev/null \
  --connect-timeout 5 --max-time 20 http://127.0.0.1:10099/
echo "PASS Kyuubi built-in web UI"

# Its REST API stays behind SPNEGO, so an anonymous call must be refused. A
# success here would mean the frontend came up unauthenticated.
kyuubi_api_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --connect-timeout 5 --max-time 20 http://127.0.0.1:10099/api/v1/ping)"
if [[ "$kyuubi_api_status" != "401" && "$kyuubi_api_status" != "403" ]]; then
  echo "Kyuubi REST API answered $kyuubi_api_status instead of refusing anonymous access" >&2
  exit 1
fi
echo "PASS Kyuubi REST API requires SPNEGO"

# The history server's own UI is deliberately unauthenticated; see the known
# limitations in README.md.
curl --fail --silent --output /dev/null \
  --connect-timeout 5 --max-time 20 http://127.0.0.1:18080/
echo "PASS Spark History web UI"

echo "All Kerberos functional checks passed."
