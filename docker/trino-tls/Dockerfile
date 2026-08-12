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

FROM alpine:3.21

RUN apk add --no-cache openssl openjdk21-jre-headless
COPY trino-tls/refresh-tls /usr/local/bin/refresh-tls
RUN chmod 0555 /usr/local/bin/refresh-tls
ENTRYPOINT ["/usr/local/bin/refresh-tls"]
