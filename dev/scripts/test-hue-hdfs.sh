#!/bin/bash
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

set -euo pipefail

# Smoke test for the reference container, not a production Hue deployment.
compose=(docker compose)
if [[ -n "${COMPOSE_FILE:-}" ]]; then
  compose+=( -f "$COMPOSE_FILE" )
fi

"${compose[@]}" exec -T hue-reference bash -lc '
  set -euo pipefail
  test -r /usr/share/hue/desktop/conf/z-kudos.ini
  test -r /etc/hadoop/conf/core-site.xml
  test -r /run/kudos-hue/admin.keytab
  export KRB5_CONFIG=/run/kudos-hue/krb5.conf
  kinit -kt /run/kudos-hue/admin.keytab admin@TEST.LOCAL
  curl --fail --silent --negotiate -u : \
    "http://hdfs.test.local:9870/webhdfs/v1/?op=LISTSTATUS" >/dev/null
  curl --fail --silent --max-time 10 http://localhost:8888/ >/dev/null
'

echo "PASS Hue reference can authenticate to and list HDFS"
