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

FROM eclipse-temurin:17-jre-jammy

ARG HBASE_VERSION=2.6.2

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install \
      -y \
      --no-install-recommends \
      curl \
      krb5-user \
      procps && \
    rm -rf /var/lib/apt/lists/* && \
    curl -fsSL \
      "https://archive.apache.org/dist/hbase/${HBASE_VERSION}/hbase-${HBASE_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt

RUN ln -s "/opt/hbase-${HBASE_VERSION}" /opt/hbase

ENV HBASE_HOME=/opt/hbase \
    HBASE_CONF_DIR=/opt/hbase/conf \
    JAVA_HOME=/opt/java/openjdk

COPY hbase-site.xml core-site.xml /opt/hbase/conf/
COPY start-hbase /usr/local/bin/start-hbase
RUN chmod 0755 /usr/local/bin/start-hbase && mkdir -p /data/hbase

EXPOSE 8080 16010 16030 2181
ENTRYPOINT ["/usr/local/bin/start-hbase"]
