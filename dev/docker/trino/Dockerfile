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

FROM trinodb/trino:483

COPY trino/etc/ /etc/trino/

# Wrapper entrypoint: sources the runtime-minted Ozone S3 secret into the env so
# the Iceberg catalog's ${ENV:KUDOS_S3_SECRET} resolves before the server starts.
COPY trino/start-trino /usr/local/bin/start-trino
USER root
RUN chmod 0755 /usr/local/bin/start-trino
USER trino
ENTRYPOINT ["/usr/local/bin/start-trino"]
