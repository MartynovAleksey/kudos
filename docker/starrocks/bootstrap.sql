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
