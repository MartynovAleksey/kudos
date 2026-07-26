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

# Seeds HBase, through the application's own API, with tables of widely varying
# size so the HBase browser UI can be exercised at scale:
#
#   * many tables            -> the table-list filter and its scroll
#   * large row counts       -> forward/back pagination of the row browser
#   * wide rows (many cols)  -> horizontal scroll and the visible-column cap
#
# Scale is parameterised. The defaults are a representative matrix that runs in
# a few minutes on the single-node test stand and covers every extreme the UI
# has to handle. Push toward the full stress target with the env vars below —
# note that a literal 5000 tables x 100000 rows is not advisable on this
# single-node Docker HBase (hundreds of millions of cells, hours, tens of GB).
#
#   MANY_TABLES=200                       tiny tables, for the list
#   ROW_SIZES="1 10 100 1000 10000 100000"  one table per row count
#   COL_SIZES="1 10 100 1000 5000"          one wide-row table per column count
#   PREFIX=gen_                            table name prefix
#   MODE=generate | clean                  clean drops every ${PREFIX}* table
#
# Usage:
#   ./scripts/hbase-loadgen.sh            # generate the representative matrix
#   MODE=clean ./scripts/hbase-loadgen.sh # remove everything it created
#   MANY_TABLES=5000 ROW_SIZES="100000" ./scripts/hbase-loadgen.sh   # heavier
set -uo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
docker_bin="$(command -v docker)"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"
admin_password="${TEST_ADMIN_PASSWORD:-K8SparkAdmin2026Secure!}"

: "${MANY_TABLES:=200}"
: "${ROW_SIZES:=1 10 100 1000 10000 100000}"
: "${COL_SIZES:=1 10 100 1000 5000}"
: "${PREFIX:=gen_}"
: "${MODE:=generate}"

run_docker() {
  env DOCKER_CONFIG="$project_root/docker/.docker-config" PATH="/usr/bin:/bin" "$docker_bin" "$@"
}
run_compose() {
  env DOCKER_CONFIG="$project_root/docker/.docker-config" PATH="/usr/bin:/bin" \
    "$compose_bin" -f "$project_root/compose.yaml" "$@"
}

vault_agent_id="$(run_compose ps -q vault-agent)"
app_ca="$(mktemp)"
trap 'rm -f "$app_ca"' EXIT
run_docker exec "$vault_agent_id" cat /vault/secrets/ca.crt >"$app_ca"
base="https://app.test.local:8443"
auth=(--resolve app.test.local:8443:127.0.0.1 --cacert "$app_ca" -u "admin:$admin_password" -s)

api_get() { curl "${auth[@]}" "$base$1"; }
api_json() {
  curl "${auth[@]}" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -X POST "$base$1" -d "$2"
}

drop_table() { api_json /api/hbase/table/delete "{\"table\":\"$1\"}" >/dev/null 2>&1; }

if [[ "$MODE" == "clean" ]]; then
  echo "Dropping ${PREFIX}* tables…"
  count=0
  for t in $(api_get /api/hbase/tables | grep -oE "\"name\":\"${PREFIX}[^\"]*\"" | sed 's/.*://;s/"//g'); do
    drop_table "$t"
    count=$((count + 1))
  done
  echo "Dropped $count tables."
  exit 0
fi

echo "Seeding HBase through $base (prefix ${PREFIX})"

# --- many tiny tables: the table list -------------------------------------
echo "Creating $MANY_TABLES tiny tables…"
for i in $(seq 1 "$MANY_TABLES"); do
  t="$(printf '%stiny_%04d' "$PREFIX" "$i")"
  drop_table "$t"
  api_json /api/hbase/table/create "{\"table\":\"$t\",\"families\":[{\"name\":\"cf\"}]}" >/dev/null
  api_json /api/hbase/row \
    "{\"table\":\"$t\",\"row\":\"r0\",\"cells\":{\"cf:a\":\"seed\"}}" >/dev/null
  [[ $((i % 50)) -eq 0 ]] && echo "  …$i"
done

# --- one table per row count: pagination ----------------------------------
for n in $ROW_SIZES; do
  t="$(printf '%srows_%d' "$PREFIX" "$n")"
  echo "Creating $t with $n rows…"
  drop_table "$t"
  api_json /api/hbase/table/create "{\"table\":\"$t\",\"families\":[{\"name\":\"cf\"}]}" >/dev/null
  csv="$(mktemp)"
  awk -v n="$n" 'BEGIN { print "key,cf:a,cf:b";
    for (i = 0; i < n; i++) printf "r%08d,value-%d,other-%d\n", i, i, i }' >"$csv"
  written="$(curl "${auth[@]}" -F "file=@$csv;type=text/csv" -X POST \
    "$base/api/hbase/bulk?table=$t")"
  echo "  wrote $written rows"
  rm -f "$csv"
done

# --- one wide-row table per column count: horizontal scroll ----------------
for c in $COL_SIZES; do
  t="$(printf '%scols_%d' "$PREFIX" "$c")"
  echo "Creating $t with a $c-column row…"
  drop_table "$t"
  api_json /api/hbase/table/create "{\"table\":\"$t\",\"families\":[{\"name\":\"cf\"}]}" >/dev/null
  cells="$(awk -v c="$c" 'BEGIN {
    printf "{";
    for (i = 0; i < c; i++) { if (i) printf ","; printf "\"cf:c%05d\":\"v%d\"", i, i }
    printf "}" }')"
  api_json /api/hbase/row "{\"table\":\"$t\",\"row\":\"wide\",\"cells\":$cells}" >/dev/null
  echo "  wrote 1 row of $c columns"
done

echo "Done. Browse them in the HBase screen; remove with MODE=clean $0"
