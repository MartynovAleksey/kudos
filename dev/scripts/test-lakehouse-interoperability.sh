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

# Real Iceberg end-to-end test: each engine writes its own table and the other
# three read it. The script deliberately does not replace a writer failure with
# a reader result: such a pair receives FAIL rather than PASS.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/../.." && pwd)"
docker_bin="$(command -v docker)"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"
test_username="${TEST_USERNAME:-admin}"
test_password="${TEST_PASSWORD:-${TEST_ADMIN_PASSWORD:-KudosAdmin2026Secure!}}"
app_base="${APP_BASE:-http://app.test.local:8443}"
namespace="${ICEBERG_E2E_NAMESPACE:-interop_e2e_$$}"
app_ca="$(mktemp)"
session_cookie="$(mktemp)"
csrf_token=""
kyuubi_session_id=""
kyuubi_flink_session_id=""
kyuubi_writer_pass=0
kyuubi_flink_writer_pass=0
trino_writer_pass=0
starrocks_writer_pass=0
failures=0
starrocks_writer_grants=0

run_compose() {
  env -u DOCKER_DEFAULT_PLATFORM DOCKER_CONFIG="$project_root/dev/docker/.docker-config" PATH="/usr/bin:/bin" \
    "$compose_bin" -f "$project_root/compose.yaml" "$@"
}

table_name() {
  local writer="$1"
  echo "interop_${writer//-/_}_$$"
}

ui_api() {
  curl --fail --silent --show-error --connect-timeout 5 --max-time 120 \
    --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
    -b "$session_cookie" -H "X-CSRF-TOKEN: $csrf_token" "$@"
}

json_field() {
  python3 -c 'import json,sys; value=json.load(sys.stdin); print(value.get(sys.argv[1], ""))' "$1"
}

sql_exec() {
  local engine="$1" sql="$2" result
  if ! result="$(ui_api -H 'Content-Type: application/json' -X POST "$app_base/ui-api/sql/execute" \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"engine":sys.argv[1],"sql":sys.argv[2]}))' "$engine" "$sql")")"; then
    return 1
  fi
  SQL_RESULT="$result"
  export SQL_RESULT
}

stop_session() {
  local engine="$1" id=""
  case "$engine" in
    kyuubi) id="$kyuubi_session_id" ;;
    kyuubi-flink) id="$kyuubi_flink_session_id" ;;
  esac
  [[ -z "$id" ]] && return 0
  ui_api -H 'Content-Type: application/json' -X POST "$app_base/ui-api/sessions/stop" \
    -d "{\"id\":\"$id\",\"engine\":\"$engine\"}" >/dev/null || true
  case "$engine" in
    kyuubi) kyuubi_session_id="" ;;
    kyuubi-flink) kyuubi_flink_session_id="" ;;
  esac
}

cleanup() {
  stop_session kyuubi
  stop_session kyuubi-flink
  if (( starrocks_writer_grants )); then
    for writer in "${engines[@]}"; do
      run_compose exec -T starrocks mysql -h 127.0.0.1 -P 9030 -uroot -e \
        "SET CATALOG iceberg; REVOKE INSERT ON TABLE $namespace.$(table_name "$writer") FROM USER 'kudos_svc'@'%'; SET CATALOG default_catalog;" \
        >/dev/null 2>&1 || true
    done
  fi
  rm -f "$app_ca" "$session_cookie"
}
trap cleanup EXIT

wait_for_session() {
  local engine="$1" id="$2" deadline=$((SECONDS + 180)) sessions
  while (( SECONDS < deadline )); do
    sessions="$(ui_api "$app_base/ui-api/sessions?engine=$engine")"
    if grep -Eq "\"id\":\"$id\"[^}]*\"state\":\"READY\"" <<<"$sessions"; then
      return 0
    fi
    if grep -Eq "\"id\":\"$id\"[^}]*\"state\":\"FAILED\"" <<<"$sessions"; then
      echo "FAIL writer/reader session $engine: $sessions" >&2
      return 1
    fi
    sleep 2
  done
  echo "FAIL timeout waiting for $engine session $id" >&2
  return 1
}

start_session() {
  local engine="$1" name="$2" params="${3:-}" started id
  started="$(ui_api -H 'Content-Type: application/json' -X POST "$app_base/ui-api/sessions/start" \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"engineParams":sys.argv[2],"engine":sys.argv[3]}))' "$name" "$params" "$engine")")"
  id="$(json_field id <<<"$started")"
  [[ -n "$id" ]] || { echo "FAIL cannot start $engine session" >&2; return 1; }
  case "$engine" in
    kyuubi) kyuubi_session_id="$id" ;;
    kyuubi-flink) kyuubi_flink_session_id="$id" ;;
  esac
  wait_for_session "$engine" "$id"
}

stop_previous_interop_sessions() {
  local engine="$1" ids id
  ids="$(ui_api "$app_base/ui-api/sessions?engine=$engine" | python3 -c '
import json, sys
for session in json.load(sys.stdin):
    if session.get("name", "").startswith("iceberg-interop-"):
        print(session["id"])
')"
  while IFS= read -r id; do
    [[ -z "$id" ]] && continue
    ui_api -H 'Content-Type: application/json' -X POST "$app_base/ui-api/sessions/stop" \
      -d "{\"id\":\"$id\",\"engine\":\"$engine\"}" >/dev/null || true
  done <<<"$ids"
}

set_writer_status() {
  local engine="$1" value="$2"
  case "$engine" in
    kyuubi) kyuubi_writer_pass="$value" ;;
    kyuubi-flink) kyuubi_flink_writer_pass="$value" ;;
    trino) trino_writer_pass="$value" ;;
    starrocks) starrocks_writer_pass="$value" ;;
  esac
}

writer_passed() {
  local engine="$1"
  case "$engine" in
    kyuubi) [[ "$kyuubi_writer_pass" == 1 ]] ;;
    kyuubi-flink) [[ "$kyuubi_flink_writer_pass" == 1 ]] ;;
    trino) [[ "$trino_writer_pass" == 1 ]] ;;
    starrocks) [[ "$starrocks_writer_pass" == 1 ]] ;;
  esac
}

run_as_writer() {
  local engine="$1" sql="$2"
  if ! sql_exec "$engine" "$sql"; then
    echo "FAIL writer=$engine SQL: $sql" >&2
    return 1
  fi
}

read_rows() {
  local engine="$1" table="$2" text_type=VARCHAR
  if [[ "$engine" == kyuubi || "$engine" == kyuubi-flink ]]; then
    text_type=STRING
  fi
  sql_exec "$engine" "SELECT writer, id, string_col, CAST(event_date AS $text_type), CAST(event_timestamp AS $text_type), int_value, CAST(decimal_value AS $text_type), double_value FROM $table ORDER BY id" || return 1
  python3 - "$engine" <<'PY'
import json, sys
result = json.loads(__import__('os').environ['SQL_RESULT'])
if not result.get('rows') or not result.get('columns'):
    raise SystemExit(f"{sys.argv[1]} returned no rows: {result}")
print(json.dumps(result['rows'], ensure_ascii=False))
PY
}

canonical_rows='''[
 ["WRITER", 1, "year-1500", "1500-01-01", "1500-01-01 00:00:00.000000", -2147483648, "-12345678901234.123456", -1.5],
 ["WRITER", 2, "julian-last", "1582-10-04", "1582-10-04 12:34:56.123456", -1, "-0.000001", -0.0],
 ["WRITER", 3, "gregorian-first", "1582-10-15", "1582-10-15 23:59:59.999999", 0, "0.000000", 0.0],
 ["WRITER", 4, "year-1899", "1899-12-31", "1899-12-31 00:00:00.000000", 1, "9999999999999.999999", 1.25],
 ["WRITER", 5, null, "1900-01-01", "1900-01-01 00:00:00.000000", 2147483647, "1.230000", 1.0e-12],
 ["WRITER", 6, "modern", "2026-08-20", "2026-08-20 10:20:30.654321", 42, "42.420000", 3.141592653589793]
]'''

compare_rows() {
  local writer="$1" engine="$2" rows_json="$3"
  WRITER="$writer" ENGINE="$engine" CANONICAL="$canonical_rows" ROWS="$rows_json" python3 <<'PY'
import datetime, decimal, json, math, os, sys
writer, engine = os.environ['WRITER'], os.environ['ENGINE']
expected = json.loads(os.environ['CANONICAL'].replace('"WRITER"', json.dumps(writer)))
actual = json.loads(os.environ['ROWS'])
errors = []
if len(actual) != len(expected):
    errors.append(f'{engine}: expected 6 rows, got {len(actual)}')
for e, a in zip(expected, actual):
    if len(a) < 8:
        errors.append(f'{engine}: short row {a!r}')
        continue
    if a[0] != e[0] or int(a[1]) != e[1] or a[2] != e[2] or str(a[3])[:10] != e[3]:
        errors.append(f'{engine}: identity/date mismatch expected {e[:4]!r}, got {a[:4]!r}')
    def ts(v):
        text = str(v).replace('T',' ').replace('Z','')
        if '.' not in text: text += '.000000'
        return text[:26]
    if ts(a[4]) != e[4]: errors.append(f'{engine}: timestamp mismatch {a[4]!r} != {e[4]!r}')
    if int(a[5]) != e[5]: errors.append(f'{engine}: int mismatch {a[5]!r} != {e[5]!r}')
    if decimal.Decimal(str(a[6])) != decimal.Decimal(e[6]): errors.append(f'{engine}: decimal mismatch {a[6]!r}')
    actual_double, expected_double = float(a[7]), e[7]
    if not math.isclose(actual_double, expected_double, rel_tol=1e-12, abs_tol=1e-12): errors.append(f'{engine}: double mismatch {a[7]!r}')
# Spark normalizes the SQL literal -0.0 to +0.0 before the value reaches an
# Iceberg reader.  Its sign is therefore not a cross-engine interoperability
# signal; numeric equality is sufficient for this matrix.
if errors:
    raise SystemExit('\n'.join(errors))
PY
}

wait_for_committed_rows() {
  local table="$1" deadline=$((SECONDS + 60)) count
  while (( SECONDS < deadline )); do
    if sql_exec kyuubi "SELECT count(*) FROM $table"; then
      count="$(python3 -c 'import json,sys; rows=json.loads(sys.stdin.read()).get("rows", []); print(rows[0][0] if rows else "")' <<<"$SQL_RESULT")"
      [[ "$count" == 6 ]] && return 0
    fi
    sleep 2
  done
  echo "writer table $table has no committed six-row Iceberg snapshot after 60 seconds" >&2
  return 1
}

verify_query_shapes() {
  local engine="$1" table="$2" timestamp_predicate
  case "$engine" in
    trino)
      # A typed literal retains all six fractional digits. CAST(... AS
      # TIMESTAMP) defaults to lower precision in Trino and rounds this
      # boundary to the next second.
      timestamp_predicate="TIMESTAMP '1582-10-15 23:59:59.999999'"
      ;;
    starrocks)
      timestamp_predicate="CAST('1582-10-15 23:59:59.999999' AS DATETIME)"
      ;;
    *)
      timestamp_predicate="CAST('1582-10-15 23:59:59.999999' AS TIMESTAMP)"
      ;;
  esac
  sql_exec "$engine" "SELECT count(*) AS row_count, sum(int_value) AS int_sum, count(string_col) AS non_null_string_count FROM $table WHERE event_date <= CAST('1900-01-01' AS DATE)" || return 1
  if ! SQL_RESULT="$SQL_RESULT" python3 - <<'PY'
import json, os
row = json.loads(os.environ['SQL_RESULT'])['rows'][0]
actual = tuple(int(value) for value in row)
if actual != (5, -1, 4):
    raise SystemExit(f'aggregate/date predicate mismatch {actual!r} != (5, -1, 4)')
PY
  then
    return 1
  fi
  sql_exec "$engine" "SELECT count(*) AS timestamp_count FROM $table WHERE event_timestamp >= $timestamp_predicate" || return 1
  if ! SQL_RESULT="$SQL_RESULT" python3 - <<'PY'
import json, os
actual = int(json.loads(os.environ['SQL_RESULT'])['rows'][0][0])
if actual != 4:
    raise SystemExit(f'timestamp predicate mismatch {actual!r} != 4')
PY
  then
    return 1
  fi
}

vault_agent_id="$(run_compose ps -q vault-agent)"
[[ -n "$vault_agent_id" ]] || { echo "vault-agent is not running" >&2; exit 1; }
env DOCKER_CONFIG="$project_root/dev/docker/.docker-config" PATH="/usr/bin:/bin" "$docker_bin" exec "$vault_agent_id" cat /vault/secrets/ca.crt >"$app_ca"
login_csrf="$(curl --fail --silent --show-error --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
  -c "$session_cookie" "$app_base/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p')"
[[ -n "$login_csrf" ]] || { echo "Cannot obtain login CSRF token" >&2; exit 1; }
curl --fail --silent --show-error --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
  -b "$session_cookie" -c "$session_cookie" \
  --data-urlencode "username=$test_username" --data-urlencode "password=$test_password" \
  --data-urlencode "_csrf=$login_csrf" "$app_base/login" >/dev/null
csrf_token="$(curl --fail --silent --show-error --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
  -b "$session_cookie" "$app_base/editor" | sed -n 's/.*meta name="_csrf" content="\([^"]*\)".*/\1/p')"
[[ -n "$csrf_token" ]] || { echo "Cannot obtain editor CSRF token" >&2; exit 1; }

declare -a engines=(kyuubi kyuubi-flink trino starrocks)
# StarRocks' external Iceberg catalog is read/write only after the table exists.
# Create all four Iceberg tables once through Spark, then test every writer's
# actual INSERT capability independently.
stop_previous_interop_sessions kyuubi
stop_previous_interop_sessions kyuubi-flink
start_session kyuubi "iceberg-interop-$$_spark"
run_as_writer kyuubi "CREATE DATABASE IF NOT EXISTS iceberg.$namespace"
for bootstrap_writer in "${engines[@]}"; do
  run_as_writer kyuubi "CREATE TABLE iceberg.$namespace.$(table_name "$bootstrap_writer") (writer STRING, id INT, string_col STRING, event_date DATE, event_timestamp TIMESTAMP, int_value INT, decimal_value DECIMAL(20,6), double_value DOUBLE) USING iceberg"
done
start_session kyuubi-flink "iceberg-interop-$$_flink" "parallelism.default=1"

# StarRocks receives a short-lived, exact-table INSERT grant. It is revoked by
# cleanup even if a later writer or reader check fails.
for grant_writer in "${engines[@]}"; do
  run_compose exec -T starrocks mysql -h 127.0.0.1 -P 9030 -uroot -e \
    "SET CATALOG iceberg; GRANT INSERT ON TABLE $namespace.$(table_name "$grant_writer") TO USER 'kudos_svc'@'%'; SET CATALOG default_catalog;"
done
starrocks_writer_grants=1

for writer in "${engines[@]}"; do
  table="iceberg.$namespace.$(table_name "$writer")"
  echo "WRITE $writer -> $table"
  set_writer_status "$writer" 0
  timestamp_type=TIMESTAMP
  null_string_type=STRING
  if [[ "$writer" == starrocks ]]; then
    timestamp_type=DATETIME
    null_string_type=VARCHAR
  elif [[ "$writer" == trino ]]; then
    null_string_type=VARCHAR
  fi
  insert_sql="INSERT INTO $table VALUES ('$writer',1,'year-1500',CAST('1500-01-01' AS DATE),CAST('1500-01-01 00:00:00' AS $timestamp_type),-2147483648,CAST('-12345678901234.123456' AS DECIMAL(20,6)),-1.5),('$writer',2,'julian-last',CAST('1582-10-04' AS DATE),CAST('1582-10-04 12:34:56.123456' AS $timestamp_type),-1,CAST('-0.000001' AS DECIMAL(20,6)),-0.0),('$writer',3,'gregorian-first',CAST('1582-10-15' AS DATE),CAST('1582-10-15 23:59:59.999999' AS $timestamp_type),0,CAST('0.000000' AS DECIMAL(20,6)),0.0),('$writer',4,'year-1899',CAST('1899-12-31' AS DATE),CAST('1899-12-31 00:00:00' AS $timestamp_type),1,CAST('9999999999999.999999' AS DECIMAL(20,6)),1.25),('$writer',5,CAST(NULL AS $null_string_type),CAST('1900-01-01' AS DATE),CAST('1900-01-01 00:00:00' AS $timestamp_type),2147483647,CAST('1.230000' AS DECIMAL(20,6)),1.0E-12),('$writer',6,'modern',CAST('2026-08-20' AS DATE),CAST('2026-08-20 10:20:30.654321' AS $timestamp_type),42,CAST('42.420000' AS DECIMAL(20,6)),3.141592653589793)"
  if ! run_as_writer "$writer" "$insert_sql"; then
    echo "FAIL writer=$writer: cannot insert canonical rows" >&2
    failures=$((failures + 1)); continue
  fi
  if ! wait_for_committed_rows "$table"; then
    echo "FAIL writer=$writer: rows were not committed" >&2
    failures=$((failures + 1)); continue
  fi
  set_writer_status "$writer" 1
done

for writer in "${engines[@]}"; do
  table="iceberg.$namespace.$(table_name "$writer")"
  if ! writer_passed "$writer"; then
    echo "SKIP readers for writer=$writer (writer did not complete)"
    failures=$((failures + 3)); continue
  fi
  for reader in "${engines[@]}"; do
    [[ "$reader" == "$writer" ]] && continue
    pair_pass=1
    if rows="$(read_rows "$reader" "$table")" && compare_rows "$writer" "$reader" "$rows"; then
      :
    else
      pair_pass=0
    fi
    if ! verify_query_shapes "$reader" "$table"; then
      pair_pass=0
    fi
    if (( pair_pass )); then
      echo "PASS $writer -> $reader"
    else
      echo "FAIL $writer -> $reader" >&2
      failures=$((failures + 1))
    fi
  done
done

if (( failures )); then
  echo "Iceberg interoperability failed: $failures checks failed" >&2
  exit 1
fi
echo "Iceberg interoperability passed: all 12 writer -> other-reader pairs verified"
