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

#
# OWASP dependency-check scan stage for the optional Maven profile -Powasp.
#
# The NVD API key is read from a FILE, never hard-coded and never a build arg:
#     key file = $OWASP_API_KEY_FILE   (default: ~/OWASP-API.key)
#
# Local run:   bash dev/scripts/scan-owasp.sh
# Docker:      the key file is mounted as a BuildKit secret at /run/secrets/owasp_key
#              and passed via OWASP_API_KEY_FILE (see dev/docker/app/Dockerfile).
#
# Output:      owasp.md in the project root (numbered Markdown table).
# Requires:    maven, python3. First run downloads the NVD database (can be slow).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

KEYFILE="${OWASP_API_KEY_FILE:-$HOME/OWASP-API.key}"
KEY=""
if [ -r "$KEYFILE" ]; then
  KEY="$(tr -d ' \t\r\n' < "$KEYFILE")"
  echo "[owasp] NVD API key loaded from $KEYFILE"
else
  echo "[owasp] no readable key file at $KEYFILE — running without a key (slower, rate-limited)" >&2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "[owasp] python3 is required to render the Markdown table." >&2
  exit 1
fi

MVN_ARGS=(-B -Dstyle.color=never -Powasp -DskipTests org.owasp:dependency-check-maven:check)
if [ -n "$KEY" ]; then
  MVN_ARGS+=("-Dnvd.api.key=$KEY")
fi

echo "[owasp] running dependency-check (first run downloads the NVD database)…"
mvn "${MVN_ARGS[@]}"

python3 scripts/scan_report_md.py owasp target/dependency-check-report.json owasp.md
echo "[owasp] report written to owasp.md"
