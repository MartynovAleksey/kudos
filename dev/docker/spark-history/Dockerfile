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

# Built from the dev/docker/ directory so the Ozone service configuration can be
# reused verbatim instead of being duplicated per service.
#
# The Ozone filesystem jar comes from the same Ozone build the stack runs, so
# the ofs:// client and the servers can never drift apart.
FROM apache/ozone:2.2.0 AS ozone

FROM apache/spark:4.1.0

USER root

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install \
      -y \
      --no-install-recommends \
      krb5-user \
      procps \
      curl && \
    rm -rf /var/lib/apt/lists/*

# Spark ships the shaded Hadoop client (hadoop-client-api/runtime), whose
# third-party classes live under org.apache.hadoop.shaded. Ozone 2.2's
# self-contained ofs:// filesystem jar tolerates that and adds ofs:// on top.
COPY --from=ozone \
  /opt/hadoop/share/ozone/lib/ozone-filesystem-hadoop3-2.2.0.jar \
  /opt/spark/jars/ozone-filesystem-hadoop3-2.2.0.jar

COPY ozone/ozone-site.xml /opt/spark/conf/ozone-site.xml
COPY spark-history/core-site.xml /opt/spark/conf/core-site.xml
COPY spark-history/spark-defaults.conf /opt/spark/conf/spark-defaults.conf
COPY spark-history/start-spark-history /usr/local/bin/start-spark-history
RUN chmod 0755 /usr/local/bin/start-spark-history

EXPOSE 18080
ENTRYPOINT ["/usr/local/bin/start-spark-history"]
