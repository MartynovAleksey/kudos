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

# Seeds the shared Iceberg lakehouse (Gravitino REST catalog, s3:// warehouse on
# Ozone) with three linked tables plus temporal and interoperability fixtures so
# the same dataset can be queried from every engine (Spark, Flink, Trino,
# StarRocks):
#
#   customers (customer_id) 1─┐
#                             └─< orders (order_id, customer_id) ─┐
#                                                                 └─< order_items (item_id, order_id)
#
# Data is fully deterministic (derived from range() ids, no randomness) so every
# run and every engine sees identical rows and joins are always non-empty. Tables
# are created with Spark SQL through Kyuubi — the same write path the engines use.
# The generated orders schema includes timestamp, integer, and floating-point
# columns: order_timestamp, priority, and discount_rate.
#
#   NAMESPACE=demo         Iceberg schema (namespace) the tables live in
#   N_CUSTOMERS=100        rows in customers
#   N_ORDERS=1000          rows in orders (each ties to a customer via id % N_CUSTOMERS)
#   N_ITEMS=5000           rows in order_items (each ties to an order via id % N_ORDERS)
#   MODE=generate | clean  clean drops the five generated tables
#
# Usage:
#   ./dev/scripts/lakehouse-loadgen.sh              # generate the dataset
#   MODE=clean ./dev/scripts/lakehouse-loadgen.sh   # remove it
#   N_ORDERS=100000 N_ITEMS=500000 ./dev/scripts/lakehouse-loadgen.sh   # heavier
set -uo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# The script lives in dev/scripts/, so the repository root is two levels up.
project_root="$(cd -- "$script_dir/../.." && pwd)"
docker_bin="$(command -v docker)"
compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"

: "${NAMESPACE:=demo}"
: "${N_CUSTOMERS:=100}"
: "${N_ORDERS:=1000}"
: "${N_ITEMS:=5000}"
: "${MODE:=generate}"
: "${CATALOG:=iceberg}"
: "${KYUUBI_PRINCIPAL:=kyuubi/kyuubi.test.local@TEST.LOCAL}"

run_docker() {
  env DOCKER_CONFIG="$project_root/dev/docker/.docker-config" PATH="/usr/bin:/bin" "$docker_bin" "$@"
}
run_compose() {
  env DOCKER_CONFIG="$project_root/dev/docker/.docker-config" PATH="/usr/bin:/bin" \
    "$compose_bin" -f "$project_root/compose.yaml" "$@"
}

kyuubi_id="$(run_compose ps -q kyuubi)"
if [[ -z "$kyuubi_id" ]]; then
  echo "kyuubi is not running; is the stand up?" >&2
  exit 1
fi

# Run a batch of Spark SQL through Kyuubi in one engine session. The SQL is piped
# to a file inside the container, then kyuubi-beeline -f runs it after a kinit as
# the kyuubi service identity (the engine reads the Iceberg S3 catalog config from
# kyuubi-defaults.conf).
run_spark_sql() {
  run_docker exec -i "$kyuubi_id" bash -c '
    set -e
    export KRB5_CONFIG=/shared/krb5.conf
    kinit -kt /shared/kyuubi.keytab '"$KYUUBI_PRINCIPAL"' 2>/dev/null
    cat > /tmp/loadgen.sql
    /opt/kyuubi/bin/kyuubi-beeline --silent=true \
      -u "jdbc:hive2://kyuubi.test.local:10009/;principal='"$KYUUBI_PRINCIPAL"'" \
      -f /tmp/loadgen.sql
  '
}

qtable() { printf '%s.%s.%s' "$CATALOG" "$NAMESPACE" "$1"; }

if [[ "$MODE" == "clean" ]]; then
  echo "Dropping lakehouse tables in $CATALOG.$NAMESPACE …"
  run_spark_sql <<SQL
DROP TABLE IF EXISTS $(qtable order_items);
DROP TABLE IF EXISTS $(qtable orders);
DROP TABLE IF EXISTS $(qtable customers);
DROP TABLE IF EXISTS $(qtable temporal_values);
DROP TABLE IF EXISTS $(qtable interop_anomaly_values);
SQL
  echo "Done."
  exit 0
fi

echo "Generating lakehouse dataset in $CATALOG.$NAMESPACE"
echo "  customers=$N_CUSTOMERS orders=$N_ORDERS order_items=$N_ITEMS"

run_spark_sql <<SQL
CREATE NAMESPACE IF NOT EXISTS $CATALOG.$NAMESPACE;

DROP TABLE IF EXISTS $(qtable order_items);
DROP TABLE IF EXISTS $(qtable orders);
DROP TABLE IF EXISTS $(qtable customers);
DROP TABLE IF EXISTS $(qtable temporal_values);
DROP TABLE IF EXISTS $(qtable interop_anomaly_values);

CREATE TABLE $(qtable customers) USING iceberg AS
SELECT
  id AS customer_id,
  concat('customer_', cast(id AS string)) AS name,
  element_at(array('Moscow','SPb','Kazan','Novosibirsk','Yekaterinburg'),
             cast(id % 5 AS int) + 1) AS city
FROM range(1, $((N_CUSTOMERS + 1)));

CREATE TABLE $(qtable orders) USING iceberg AS
SELECT
  id AS order_id,
  cast(id % $N_CUSTOMERS AS bigint) + 1 AS customer_id,
  cast(10 + (id * 7 % 990) AS decimal(10,2)) AS amount,
  cast(date_add(date'2024-01-01', cast(id % 365 AS int)) AS timestamp) AS order_timestamp,
  cast(id % 5 AS int) + 1 AS priority,
  cast((id % 25) / 100.0 AS double) AS discount_rate,
  date_add(date'2024-01-01', cast(id % 365 AS int)) AS order_date
FROM range(1, $((N_ORDERS + 1)));

CREATE TABLE $(qtable order_items) USING iceberg AS
SELECT
  id AS item_id,
  cast(id % $N_ORDERS AS bigint) + 1 AS order_id,
  concat('product_', cast(id % 20 AS string)) AS product,
  cast(id % 5 AS int) + 1 AS qty,
  cast(5 + (id * 3 % 195) AS decimal(10,2)) AS price
FROM range(1, $((N_ITEMS + 1)));

CREATE TABLE $(qtable temporal_values) (
  event_timestamp TIMESTAMP,
  event_date DATE
) USING iceberg;

INSERT INTO $(qtable temporal_values) VALUES(CAST ('1900-01-01 00:00:00' AS TIMESTAMP), CAST('1900-01-01' AS DATE));

CREATE TABLE $(qtable interop_anomaly_values) (
  id INT,
  string_col STRING,
  event_date DATE,
  event_timestamp TIMESTAMP,
  int_value INT,
  decimal_value DECIMAL(20,6),
  double_value DOUBLE
) USING iceberg;

-- Spark writes this fixture intentionally. It contains the historical calendar
-- boundaries and signed zero used by the interoperability reproducer.
INSERT INTO $(qtable interop_anomaly_values) VALUES
  (1, 'year-1500', CAST('1500-01-01' AS DATE), CAST('1500-01-01 00:00:00' AS TIMESTAMP), -2147483648, CAST('-12345678901234.123456' AS DECIMAL(20,6)), -1.5),
  (2, 'julian-last', CAST('1582-10-04' AS DATE), CAST('1582-10-04 12:34:56.123456' AS TIMESTAMP), -1, CAST('-0.000001' AS DECIMAL(20,6)), -0.0),
  (3, 'gregorian-first', CAST('1582-10-15' AS DATE), CAST('1582-10-15 23:59:59.999999' AS TIMESTAMP), 0, CAST('0.000000' AS DECIMAL(20,6)), 0.0),
  (4, 'year-1899', CAST('1899-12-31' AS DATE), CAST('1899-12-31 00:00:00' AS TIMESTAMP), 1, CAST('9999999999999.999999' AS DECIMAL(20,6)), 1.25),
  (5, NULL, CAST('1900-01-01' AS DATE), CAST('1900-01-01 00:00:00' AS TIMESTAMP), 2147483647, CAST('1.230000' AS DECIMAL(20,6)), -0.0),
  (6, 'modern', CAST('2026-08-20' AS DATE), CAST('2026-08-20 10:20:30.654321' AS TIMESTAMP), 42, CAST('42.420000' AS DECIMAL(20,6)), 3.141592653589793);

SELECT 'customers' AS table, count(*) AS rows FROM $(qtable customers)
UNION ALL SELECT 'orders', count(*) FROM $(qtable orders)
UNION ALL SELECT 'order_items', count(*) FROM $(qtable order_items)
UNION ALL SELECT 'temporal_values', count(*) FROM $(qtable temporal_values)
UNION ALL SELECT 'interop_anomaly_values', count(*) FROM $(qtable interop_anomaly_values);
SQL

echo "Done. Query the dataset from any engine with dev/sql/<engine>/*.sql"
