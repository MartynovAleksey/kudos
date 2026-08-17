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

# Validates SQL Editor through the application; queries do not use tables or real data.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
docker_bin="$(command -v docker)"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"
test_username="${TEST_USERNAME:-admin}"
test_password="${TEST_PASSWORD:-${TEST_ADMIN_PASSWORD:-KudosAdmin2026Secure!}}"
app_base="${APP_BASE:-http://app.test.local:8443}"
app_ca="$(mktemp)"
session_cookie="$(mktemp)"
csrf_token=""
spark_session_id=""
spark_engine_session_id=""
flink_session_id=""

run_docker() {
  env DOCKER_CONFIG="$project_root/dev/docker/.docker-config" PATH="/usr/bin:/bin" "$docker_bin" "$@"
}

run_compose() {
  env -u DOCKER_DEFAULT_PLATFORM DOCKER_CONFIG="$project_root/dev/docker/.docker-config" PATH="/usr/bin:/bin" \
    "$compose_bin" -f "$project_root/compose.yaml" "$@"
}

ui_api() {
  curl --fail --silent --show-error --connect-timeout 5 --max-time 90 \
    --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
    -b "$session_cookie" -H "X-CSRF-TOKEN: $csrf_token" "$@"
}

stop_session() {
  local engine="$1"
  local session_id="$2"
  [[ -z "$session_id" ]] && return 0
  ui_api -H 'Content-Type: application/json' -X POST "$app_base/ui-api/sessions/stop" \
    -d "{\"id\":\"$session_id\",\"engine\":\"$engine\"}" >/dev/null || true
}

cleanup() {
  stop_session kyuubi "$spark_session_id"
  stop_session kyuubi-flink "$flink_session_id"
  rm -f "$app_ca" "$session_cookie"
}
trap cleanup EXIT

assert_active_session() {
  local engine="$1"
  local session_id="$2"
  local sessions
  sessions="$(ui_api "$app_base/ui-api/sessions?engine=$engine")"
  if ! grep -Eq "\"id\":\"$session_id\"[^}]*\"active\":true" <<<"$sessions"; then
    echo "Kyuubi session $session_id is not active for $engine" >&2
    return 1
  fi
}

wait_for_session() {
  local engine="$1"
  local session_id="$2"
  local deadline=$((SECONDS + 180))
  local sessions
  while (( SECONDS < deadline )); do
    sessions="$(ui_api "$app_base/ui-api/sessions?engine=$engine")"
    if grep -Eq "\"id\":\"$session_id\"[^}]*\"state\":\"READY\"" <<<"$sessions"; then
      assert_active_session "$engine" "$session_id"
      return 0
    fi
    if grep -Eq "\"id\":\"$session_id\"[^}]*\"state\":\"FAILED\"" <<<"$sessions"; then
      echo "Kyuubi session failed: $sessions" >&2
      return 1
    fi
    sleep 2
  done
  echo "Timed out waiting for Kyuubi session $session_id ($engine)" >&2
  return 1
}

run_queries() {
  local engine="$1"
  local session_id="$2"
  shift 2
  local query_number=0
  local sql
  local result
  for sql in "$@"; do
    query_number=$((query_number + 1))
    if ! result="$(ui_api -H 'Content-Type: application/json' -X POST "$app_base/ui-api/sql/execute" \
      -d "{\"engine\":\"$engine\",\"sql\":\"$sql\"}")"; then
      echo "SQL failed for $engine query $query_number: $sql" >&2
      return 1
    fi
    if ! grep -q '"columns":' <<<"$result" || ! grep -q '"rows":' <<<"$result"; then
      echo "Unexpected result for $engine query $query_number: $result" >&2
      return 1
    fi
    if [[ -n "$session_id" ]]; then
      assert_active_session "$engine" "$session_id"
    fi
    printf 'PASS %s SQL %d/10\n' "$engine" "$query_number"
  done
}

test_kyuubi_engine() {
  local engine="$1"
  local session_name="$2"
  local params="$3"
  shift 3
  local started
  local session_id
  local monitor
  started="$(ui_api -H 'Content-Type: application/json' -X POST "$app_base/ui-api/sessions/start" \
    -d "{\"name\":\"$session_name\",\"engineParams\":\"$params\",\"engine\":\"$engine\"}")"
  session_id="$(sed -n 's/.*\"id\":\"\([^\"]*\)\".*/\1/p' <<<"$started")"
  [[ -n "$session_id" ]]
  if [[ "$engine" == "kyuubi" ]]; then
    spark_session_id="$session_id"
  else
    flink_session_id="$session_id"
  fi
  wait_for_session "$engine" "$session_id"
  if [[ "$engine" == "kyuubi" ]]; then
    spark_engine_session_id="$(ui_api "$app_base/ui-api/sessions?engine=kyuubi" \
      | grep -o "\"id\":\"$session_id\"[^}]*" \
      | sed -n 's/.*"kyuubiSessionId":"\([^"]*\)".*/\1/p')"
    [[ -n "$spark_engine_session_id" ]]
  fi
  run_queries "$engine" "$session_id" "$@"
  monitor="$(ui_api "$app_base/ui-api/sessions/$session_id/monitor?engine=$engine")"
  grep -Eq '"totalOperations":1[0-9]|"totalOperations":[2-9][0-9]' <<<"$monitor"
  printf 'PASS %s: 10 SQL queries ran in one session %s\n' "$engine" "$session_id"
  stop_session "$engine" "$session_id"
  if [[ "$engine" == "kyuubi" ]]; then
    wait_for_spark_history
    spark_session_id=""
    spark_engine_session_id=""
  else
    flink_session_id=""
  fi
}

wait_for_spark_history() {
  local deadline=$((SECONDS + 180))
  local applications
  local expected_name="kyuubi_CONNECTION_SPARK_SQL_${test_username}_${spark_engine_session_id}"
  while (( SECONDS < deadline )); do
    applications="$(ui_api "$app_base/ui-api/spark/applications?limit=20")"
    if grep -Eq "\"name\":\"$expected_name\",\"user\":\"$test_username\"[^}]*\"completed\":true" \
      <<<"$applications"; then
      echo "PASS Kyuubi Spark event log in Ozone and History for $test_username"
      return 0
    fi
    sleep 3
  done
  echo "Spark History did not return completed application $expected_name for $test_username" >&2
  return 1
}

spark_queries=(
  "SELECT 1"
  "SELECT 1 + 2"
  "SELECT (7 * 6) - 1"
  "SELECT upper('kudos')"
  "SELECT concat('k', 'udos')"
  "SELECT CASE WHEN 2 > 1 THEN 'yes' ELSE 'no' END"
  "SELECT cast(42 AS string)"
  "SELECT size(array(1, 2, 3))"
  "SELECT coalesce(cast(NULL AS string), 'fallback')"
  "SELECT count(*) FROM (SELECT 1 AS value UNION ALL SELECT 2 UNION ALL SELECT 3) t"
)

flink_queries=(
  "SELECT 1"
  "SELECT 1 + 2"
  "SELECT (7 * 6) - 1"
  "SELECT UPPER('kudos')"
  "SELECT CONCAT('k', 'udos')"
  "SELECT CASE WHEN 2 > 1 THEN 'yes' ELSE 'no' END"
  "SELECT CAST(42 AS STRING)"
  "SELECT CHAR_LENGTH('kudos')"
  "SELECT COALESCE(CAST(NULL AS STRING), 'fallback')"
  "SELECT POWER(2, 5)"
)

trino_queries=(
  "SELECT 1"
  "SELECT 1 + 2"
  "SELECT (7 * 6) - 1"
  "SELECT upper('kudos')"
  "SELECT concat('k', 'udos')"
  "SELECT CASE WHEN 2 > 1 THEN 'yes' ELSE 'no' END"
  "SELECT CAST(42 AS varchar)"
  "SELECT cardinality(ARRAY[1, 2, 3])"
  "SELECT coalesce(CAST(NULL AS varchar), 'fallback')"
  "SELECT count(*) FROM (VALUES 1, 2, 3) AS t(value)"
)

starrocks_queries=(
  "SELECT 1"
  "SELECT 1 + 2"
  "SELECT (7 * 6) - 1"
  "SELECT UPPER('kudos')"
  "SELECT CONCAT('k', 'udos')"
  "SELECT CASE WHEN 2 > 1 THEN 'yes' ELSE 'no' END"
  "SELECT CAST(42 AS CHAR)"
  "SELECT (100 / 4) + 2"
  "SELECT 10 % 3"
  "SELECT 5 IN (1, 5, 9)"
)

vault_agent_id="$(run_compose ps -q vault-agent)"
[[ -n "$vault_agent_id" ]]
run_docker exec "$vault_agent_id" cat /vault/secrets/ca.crt >"$app_ca"
login_csrf="$(curl --fail --silent --show-error --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
  -c "$session_cookie" "$app_base/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p')"
[[ -n "$login_csrf" ]]
curl --fail --silent --show-error --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
  -b "$session_cookie" -c "$session_cookie" \
  --data-urlencode "username=$test_username" --data-urlencode "password=$test_password" \
  --data-urlencode "_csrf=$login_csrf" "$app_base/login" >/dev/null
csrf_token="$(curl --fail --silent --show-error --resolve "app.test.local:8443:127.0.0.1" --cacert "$app_ca" \
  -b "$session_cookie" "$app_base/editor" | sed -n 's/.*meta name="_csrf" content="\([^"]*\)".*/\1/p')"
[[ -n "$csrf_token" ]]

test_kyuubi_engine kyuubi "sql-e2e-spark-$$" "" "${spark_queries[@]}"
test_kyuubi_engine kyuubi-flink "sql-e2e-flink-$$" "parallelism.default=1" "${flink_queries[@]}"
run_queries trino "" "${trino_queries[@]}"
run_queries starrocks "" "${starrocks_queries[@]}"

echo "All 40 SQL Editor queries passed. Kyuubi Spark and Flink used one session each."
