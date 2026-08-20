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

-- StarRocks analytics over the shared Iceberg lakehouse.
-- Dataset: dev/scripts/lakehouse-loadgen.sh (customers -> orders -> order_items).
-- The external `iceberg` catalog is created by dev/docker/starrocks/bootstrap.sql
-- (Iceberg REST + native S3). SET CATALOG makes it current so tables are addressed
-- as <db>.<table>. Same logic as the Spark/Flink/Trino scripts — results must match
-- for one dataset snapshot.
SET CATALOG iceberg;

-- Orders and total amount per city (customers ⋈ orders).
SELECT c.city,
       count(DISTINCT o.order_id) AS orders,
       round(sum(o.amount), 2)    AS total_amount
FROM demo.orders o
JOIN demo.customers c ON o.customer_id = c.customer_id
GROUP BY c.city
ORDER BY total_amount DESC;

-- Units and item revenue per city (order_items ⋈ orders ⋈ customers).
SELECT c.city,
       sum(oi.qty)                      AS units,
       round(sum(oi.qty * oi.price), 2) AS item_revenue
FROM demo.order_items oi
JOIN demo.orders o    ON oi.order_id = o.order_id
JOIN demo.customers c ON o.customer_id = c.customer_id
GROUP BY c.city
ORDER BY item_revenue DESC;
