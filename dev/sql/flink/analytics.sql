/*
 * Copyright 2026 Aleksey Martynov and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- Flink SQL (Kyuubi Flink engine) analytics over the shared Iceberg lakehouse.
-- Dataset: dev/scripts/lakehouse-loadgen.sh (customers -> orders -> order_items).
-- The Flink engine loads the `iceberg` catalog from its file catalog store
-- (dev/docker/kyuubi/flink-catalog-store/iceberg.yaml), so tables are addressed
-- as iceberg.<schema>.<table> with no CREATE/USE CATALOG — same as the Spark/
-- Trino/StarRocks scripts. Results must match for one dataset snapshot.

-- Orders and total amount per city (customers ⋈ orders).
SELECT c.city,
       count(DISTINCT o.order_id) AS orders,
       round(sum(o.amount), 2)    AS total_amount
FROM iceberg.demo.orders o
JOIN iceberg.demo.customers c ON o.customer_id = c.customer_id
GROUP BY c.city
ORDER BY total_amount DESC;

-- Units and item revenue per city (order_items ⋈ orders ⋈ customers).
SELECT c.city,
       sum(oi.qty)                      AS units,
       round(sum(oi.qty * oi.price), 2) AS item_revenue
FROM iceberg.demo.order_items oi
JOIN iceberg.demo.orders o    ON oi.order_id = o.order_id
JOIN iceberg.demo.customers c ON o.customer_id = c.customer_id
GROUP BY c.city
ORDER BY item_revenue DESC;
