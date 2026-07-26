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
# Trivy scan stage for the optional Maven profile -Ptrivy.
#
# Scans the built Spring Boot fat-jar (which bundles every runtime dependency
# under BOOT-INF/lib, so nested/shaded jars are seen too) and writes a numbered
# Markdown table to trivy.md in the project root.
#
# Requires: trivy on PATH (https://trivy.dev), python3.
# Optional:  TRIVY_IMAGE=<image:tag> also scans that container image.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v trivy >/dev/null 2>&1; then
  echo "[trivy] not found on PATH. Install: https://trivy.dev/latest/getting-started/installation/" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "[trivy] python3 is required to render the Markdown table." >&2
  exit 1
fi

JAR="$(ls -1 target/kudos-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc\|\.original' | head -1 || true)"
if [ -z "$JAR" ]; then
  echo "[trivy] built jar not found in target/. Run 'mvn package' first (or 'mvn -Ptrivy verify')." >&2
  exit 1
fi

mkdir -p target
echo "[trivy] scanning $JAR"
# Use `rootfs`, not `fs`: the `fs` mode targets lockfiles/source and does NOT
# run the jar analyzer, so it reports nothing for a Spring Boot executable jar.
# `rootfs` treats the jar as a root filesystem and walks BOOT-INF/lib, resolving
# each bundled dependency (incl. shaded fat-jars via their pom.properties).
trivy rootfs --scanners vuln --format json --output target/trivy.json "$JAR"

# Optionally fold in a container image scan (best effort; appended by Trivy into
# the same JSON is not supported, so only the jar drives trivy.md by default).
if [ -n "${TRIVY_IMAGE:-}" ]; then
  echo "[trivy] scanning image ${TRIVY_IMAGE} -> target/trivy-image.json"
  trivy image --scanners vuln --format json --output target/trivy-image.json "${TRIVY_IMAGE}" || \
    echo "[trivy] image scan failed (continuing with jar results)" >&2
fi

python3 scripts/scan_report_md.py trivy target/trivy.json trivy.md
echo "[trivy] report written to trivy.md"
