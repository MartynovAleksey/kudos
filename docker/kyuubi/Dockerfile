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

# Built from the docker/ directory so the Ozone service configuration can be
# reused verbatim instead of being duplicated per service.
#
# The Ozone filesystem jar is taken from the same Ozone build the stack runs,
# so the ofs:// client and the servers can never drift apart.
FROM apache/ozone:2.0.0 AS ozone
FROM apache/hadoop:3.4.2 AS hadoop
FROM flink:1.20 AS flink

FROM apache/kyuubi:1.10.1-spark

USER root

ENV JAVA_TOOL_OPTIONS=-Djava.security.krb5.conf=/shared/krb5.conf

COPY kyuubi/kyuubi-defaults.conf /opt/kyuubi/conf/kyuubi-defaults.conf
COPY kyuubi/core-site.xml kyuubi/hive-site.xml /opt/kyuubi/conf/
COPY kyuubi/start-kerberos-kyuubi /opt/kyuubi/bin/start-kerberos-kyuubi

# The engines write their event logs to ofs://, which the bundled Spark cannot
# resolve on its own. SPARK_HOME is resolved by glob so a base image carrying a
# different Spark build fails the build instead of silently skipping this.
#
# Spark ships the shaded Hadoop client (hadoop-client-api/runtime), whose
# third-party classes live under org.apache.hadoop.shaded. The -client build of
# the Ozone filesystem is the one relocated to match; the plain build expects an
# unshaded Hadoop and is what the Spring application uses instead.
COPY --from=ozone \
  /opt/hadoop/share/ozone/lib/ozone-filesystem-hadoop3-client-2.0.0.jar \
  /tmp/ozone-filesystem-hadoop3.jar
COPY ozone/ozone-site.xml /tmp/ozone-site.xml
COPY --from=flink /opt/flink /opt/flink
COPY --from=hadoop /opt/hadoop /opt/hadoop
RUN set -eux; \
    spark_home="$(echo /opt/kyuubi/externals/spark-*-bin-hadoop3)"; \
    test -d "$spark_home/jars"; \
    mv /tmp/ozone-filesystem-hadoop3.jar "$spark_home/jars/"; \
    cp /tmp/ozone-site.xml /opt/kyuubi/conf/core-site.xml; \
    cp /tmp/ozone-site.xml "$spark_home/conf/"; \
    cp /tmp/ozone-site.xml /opt/flink/conf/; \
    cp /tmp/ozone-site.xml /opt/hadoop/etc/hadoop/core-site.xml; \
    rm -f /tmp/ozone-site.xml; \
    sed -i 's/^    address: localhost$/    address: flink-jobmanager.test.local/' /opt/flink/conf/config.yaml; \
    sed -i 's/^  address: 0.0.0.0$/  address: flink-jobmanager.test.local/' /opt/flink/conf/config.yaml; \
    sed -i '/^rest:$/a\  port: 8081' /opt/flink/conf/config.yaml; \
    mkdir -p /opt/kyuubi/externals/engines/flink/scala-2.12; \
    ln -s /opt/flink/lib /opt/kyuubi/externals/engines/flink/scala-2.12/jars; \
    chmod 0755 /opt/kyuubi/bin/start-kerberos-kyuubi; \
    chown -R 10009:root /opt/kyuubi/conf "$spark_home/conf" "$spark_home/jars" /opt/flink /opt/hadoop

USER 10009
ENV FLINK_HOME=/opt/flink
ENV HADOOP_HOME=/opt/hadoop
ENV HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
ENV FLINK_HADOOP_CLASSPATH=/opt/hadoop/share/hadoop/common/*:/opt/hadoop/share/hadoop/common/lib/*:/opt/hadoop/share/hadoop/hdfs/*:/opt/hadoop/share/hadoop/hdfs/lib/*:/opt/hadoop/share/hadoop/yarn/*:/opt/hadoop/share/hadoop/yarn/lib/*:/opt/hadoop/share/hadoop/mapreduce/*:/opt/hadoop/share/hadoop/mapreduce/lib/*
ENTRYPOINT ["/opt/kyuubi/bin/start-kerberos-kyuubi"]
