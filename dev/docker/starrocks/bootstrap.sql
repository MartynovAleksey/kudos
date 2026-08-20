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

CREATE DATABASE IF NOT EXISTS kudos_test;
CREATE USER IF NOT EXISTS 'kudos_svc' IDENTIFIED BY '__KUDOS_STARROCKS_PASSWORD__';
ALTER USER 'kudos_svc' IDENTIFIED BY '__KUDOS_STARROCKS_PASSWORD__';
GRANT ALL ON DATABASE kudos_test TO USER 'kudos_svc'@'%';
GRANT ALL ON ALL TABLES IN DATABASE kudos_test TO USER 'kudos_svc'@'%';

-- The shared Iceberg lakehouse catalog, backed by Gravitino's Iceberg REST
-- endpoint. StarRocks reads the warehouse over the Ozone S3 Gateway (s3://) with
-- its native S3 support, so it sees the same tables as Spark/Flink/Trino. The
-- admin S3 credentials sign requests; path-style URLs avoid per-bucket DNS.
-- vended-credentials-enabled=false: StarRocks' Iceberg REST client otherwise asks
-- Gravitino to VEND S3 credentials (X-Iceberg-Access-Delegation), which this
-- Gravitino build cannot do ("No credential provider found for path s3://…") and
-- loadTable fails. Disabling it makes StarRocks read metadata with its own aws.s3
-- credentials, like Trino.
DROP CATALOG IF EXISTS iceberg;
CREATE EXTERNAL CATALOG iceberg PROPERTIES (
  "type" = "iceberg",
  "iceberg.catalog.type" = "rest",
  "iceberg.catalog.uri" = "http://gravitino.test.local:9001/iceberg/",
  "iceberg.catalog.vended-credentials-enabled" = "false",
  "aws.s3.endpoint" = "http://ozone.test.local:9878",
  "aws.s3.enable_path_style_access" = "true",
  "aws.s3.region" = "us-east-1",
  "aws.s3.access_key" = "admin@TEST.LOCAL",
  "aws.s3.secret_key" = "__KUDOS_S3_SECRET__"
);
GRANT USAGE ON CATALOG iceberg TO USER 'kudos_svc'@'%';
-- USAGE lets the service account see the catalog; SELECT on its tables is a
-- separate grant, and external-catalog grants are scoped to the current catalog,
-- so switch into it first. Without this KUDOS (which queries as kudos_svc, not
-- root) gets "Access denied … SELECT privilege(s) on TABLE".
SET CATALOG iceberg;
GRANT SELECT ON ALL TABLES IN ALL DATABASES TO USER 'kudos_svc'@'%';
SET CATALOG default_catalog;
