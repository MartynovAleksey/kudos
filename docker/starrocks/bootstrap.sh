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

set -euo pipefail

password="$(cat /vault/secrets/starrocks-password)"
escaped_password="$(printf '%s' "$password" | sed "s/'/''/g")"
sed "s/__KUDOS_STARROCKS_PASSWORD__/$escaped_password/g" /bootstrap/bootstrap.sql \
  | mysql -h starrocks.test.local -P 9030 -uroot
