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

# Fails if the application regains a native HBase client/RPC dependency.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
dependencies="$(mktemp)"
trap 'rm -f "$dependencies"' EXIT

cd "$project_root"
mvn -q dependency:tree \
  -Dincludes=org.apache.hbase:hbase-client,org.apache.hbase:hbase-common \
  -DoutputFile="$dependencies" \
  -DoutputType=text

if grep -Eq 'org\.apache\.hbase:(hbase-client|hbase-common)' "$dependencies"; then
  echo "Native HBase client dependency found:" >&2
  cat "$dependencies" >&2
  exit 1
fi

if rg -n \
  'org\.apache\.hadoop\.hbase|ConnectionFactory|HBaseConfiguration|hbaseQuorum|HBASE_ZK_QUORUM' \
  src/main/java; then
  echo "Native HBase RPC/ZooKeeper usage found in production source." >&2
  exit 1
fi

artifact="target/kudos-0.1.0.jar"
if [[ -f "$artifact" ]] && jar tf "$artifact" | grep -Eq 'BOOT-INF/lib/hbase-(client|common)'; then
  echo "Packaged Kudos jar contains a native HBase client." >&2
  exit 1
fi

echo "PASS no native HBase client or RPC/ZooKeeper path in Kudos"
