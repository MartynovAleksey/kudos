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
# reused verbatim, matching how dev/docker/spark-history/Dockerfile is built.
#
# Flink stores its completed-job archives in the same Kerberized Ozone the rest
# of the stack uses. Flink ships Hadoop-free, so a vanilla Hadoop provides the
# (unshaded) FileSystem and UGI its Hadoop integration needs, and the plain
# (non-client) Ozone filesystem jar from the same Ozone build adds ofs:// on top.
FROM apache/hadoop:3.4.2 AS hadoop
FROM apache/ozone:2.0.0 AS ozone

FROM flink:1.20

USER root

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install \
      -y \
      --no-install-recommends \
      krb5-user \
      procps && \
    rm -rf /var/lib/apt/lists/*

COPY --from=hadoop /opt/hadoop /opt/hadoop
COPY --from=ozone \
  /opt/hadoop/share/ozone/lib/ozone-filesystem-hadoop3-2.0.0.jar \
  /opt/hadoop/share/hadoop/common/lib/ozone-filesystem-hadoop3-2.0.0.jar

COPY ozone/ozone-site.xml /opt/hadoop/etc/hadoop/ozone-site.xml
COPY flink/core-site.xml /opt/hadoop/etc/hadoop/core-site.xml
COPY flink/flink-entrypoint /usr/local/bin/flink-entrypoint
RUN chmod 0755 /usr/local/bin/flink-entrypoint

ENV HADOOP_HOME=/opt/hadoop
ENV HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop

# The stock image drops privileges to the flink user itself; this only prepends
# the Hadoop classpath and the keytab wait.
ENTRYPOINT ["/usr/local/bin/flink-entrypoint"]
