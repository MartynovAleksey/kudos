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
# The Ozone filesystem jar is taken from the same Ozone build the stack runs,
# so the ofs:// client and the servers can never drift apart.
FROM apache/ozone:2.2.0 AS ozone
FROM apache/hadoop:3.4.2 AS hadoop
FROM flink:1.20 AS flink

# Iceberg runtimes for Spark 3.5/Scala 2.12 and Flink 1.20, matching Gravitino's
# Iceberg (1.11.0). The engines run on Java 17 (installed below), so the modern
# Java-17 Iceberg build is used for both.
FROM curlimages/curl:8.11.1 AS iceberg
ARG ICEBERG_VERSION=1.11.0
RUN ib=https://repo1.maven.org/maven2/org/apache/iceberg; \
  curl -fsSL -o /tmp/iceberg-spark-runtime.jar \
    "$ib/iceberg-spark-runtime-3.5_2.12/${ICEBERG_VERSION}/iceberg-spark-runtime-3.5_2.12-${ICEBERG_VERSION}.jar"; \
  curl -fsSL -o /tmp/iceberg-flink-runtime.jar \
    "$ib/iceberg-flink-runtime-1.20/${ICEBERG_VERSION}/iceberg-flink-runtime-1.20-${ICEBERG_VERSION}.jar"; \
  # S3FileIO + bundled AWS SDK, so Spark and Flink read the warehouse over the
  # Ozone S3 Gateway (s3://) — the scheme Trino and StarRocks also use.
  curl -fsSL -o /tmp/iceberg-aws-bundle.jar \
    "$ib/iceberg-aws-bundle/${ICEBERG_VERSION}/iceberg-aws-bundle-${ICEBERG_VERSION}.jar"

FROM apache/kyuubi:1.12.0-spark

USER root

# Kyuubi 1.12 still ships Java 8, but Flink 1.20 and modern Iceberg need Java 11+.
# Install Java 17 (max supported by Spark 3.5 / Flink 1.20 / Kyuubi 1.12) and make
# it the default. The FreeIPA keytabs stay on AES-SHA1, which Java 17 supports.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

ENV JAVA_TOOL_OPTIONS=-Djava.security.krb5.conf=/shared/krb5.conf

COPY kyuubi/kyuubi-defaults.conf /opt/kyuubi/conf/kyuubi-defaults.conf
COPY kyuubi/core-site.xml kyuubi/hive-site.xml /opt/kyuubi/conf/
COPY kyuubi/start-kerberos-kyuubi /opt/kyuubi/bin/start-kerberos-kyuubi

# The engines write their event logs to ofs://, which the bundled Spark cannot
# resolve on its own. SPARK_HOME is resolved by glob so a base image carrying a
# different Spark build fails the build instead of silently skipping this.
#
# Ozone 2.2 ships one self-contained ofs:// filesystem jar (the separate -client
# build is gone). The same jar serves both engines, in the place each looks:
#  * Spark loads it from its own jars/ (its shaded Hadoop client tolerates the
#    jar's relocated internals).
#  * Flink is Hadoop-free and runs against a vanilla unshaded Hadoop on
#    HADOOP_CLASSPATH, so it loads the jar from Hadoop's common/lib, exactly like
#    the standalone dev/docker/flink cluster that already writes ofs://.
COPY --from=ozone \
  /opt/hadoop/share/ozone/lib/ozone-filesystem-hadoop3-2.2.0.jar \
  /tmp/ozone-fs-spark.jar
COPY --from=ozone \
  /opt/hadoop/share/ozone/lib/ozone-filesystem-hadoop3-2.2.0.jar \
  /tmp/ozone-fs-flink.jar
COPY --from=iceberg /tmp/iceberg-spark-runtime.jar /tmp/iceberg-spark-runtime.jar
COPY --from=iceberg /tmp/iceberg-flink-runtime.jar /tmp/iceberg-flink-runtime.jar
COPY --from=iceberg /tmp/iceberg-aws-bundle.jar /tmp/iceberg-aws-bundle-spark.jar
COPY --from=iceberg /tmp/iceberg-aws-bundle.jar /tmp/iceberg-aws-bundle-flink.jar
COPY ozone/ozone-site.xml /tmp/ozone-site.xml
# Flink file catalog store entry for the shared Iceberg catalog (see the RUN
# below); the runtime S3 secret is templated in by start-kerberos-kyuubi.
COPY kyuubi/flink-catalog-store/iceberg.yaml /tmp/flink-iceberg-catalog.yaml
COPY --from=flink /opt/flink /opt/flink
COPY --from=hadoop /opt/hadoop /opt/hadoop
RUN set -eux; \
    spark_home="$(echo /opt/kyuubi/externals/spark-*-bin-hadoop3)"; \
    test -d "$spark_home/jars"; \
    mv /tmp/ozone-fs-spark.jar "$spark_home/jars/ozone-filesystem-hadoop3-2.2.0.jar"; \
    mv /tmp/ozone-fs-flink.jar /opt/hadoop/share/hadoop/common/lib/ozone-filesystem-hadoop3-2.2.0.jar; \
    # Ozone 2.2's filesystem targets Hadoop 3.4 and implements fs interfaces
    # (LeaseRecoverable, SafeMode, BulkDelete, ...) absent from Spark 3.5's bundled
    # Hadoop 3.3.4. Shim just those additive interfaces onto Spark's classpath from
    # the Hadoop 3.4.2 the Flink side already uses. (Flink itself needs no shim.)
    mkdir -p /tmp/fsshim; \
    ( cd /tmp/fsshim \
      && jar xf /opt/hadoop/share/hadoop/common/hadoop-common-3.4.2.jar \
           org/apache/hadoop/fs/LeaseRecoverable.class \
           org/apache/hadoop/fs/SafeMode.class \
           org/apache/hadoop/fs/SafeModeAction.class \
           org/apache/hadoop/fs/BulkDelete.class \
           org/apache/hadoop/fs/BulkDeleteSource.class \
           org/apache/hadoop/fs/BulkDeleteUtils.class \
      && jar cf "$spark_home/jars/ozone-hadoop34-fs-shim.jar" org ); \
    rm -rf /tmp/fsshim; \
    mv /tmp/iceberg-spark-runtime.jar "$spark_home/jars/"; \
    mv /tmp/iceberg-flink-runtime.jar /opt/flink/lib/; \
    mv /tmp/iceberg-aws-bundle-spark.jar "$spark_home/jars/iceberg-aws-bundle.jar"; \
    mv /tmp/iceberg-aws-bundle-flink.jar /opt/flink/lib/iceberg-aws-bundle.jar; \
    cp /tmp/ozone-site.xml /opt/kyuubi/conf/core-site.xml; \
    cp /tmp/ozone-site.xml "$spark_home/conf/"; \
    cp /tmp/ozone-site.xml /opt/flink/conf/; \
    cp /tmp/ozone-site.xml /opt/hadoop/etc/hadoop/core-site.xml; \
    sed -i 's#</configuration>#  <property><name>fs.ofs.impl</name><value>org.apache.hadoop.fs.ozone.RootedOzoneFileSystem</value></property>\n</configuration>#' /opt/hadoop/etc/hadoop/core-site.xml; \
    rm -f /tmp/ozone-site.xml; \
    sed -i 's/^    address: localhost$/    address: flink-jobmanager.test.local/' /opt/flink/conf/config.yaml; \
    sed -i 's/^  address: 0.0.0.0$/  address: flink-jobmanager.test.local/' /opt/flink/conf/config.yaml; \
    sed -i '/^rest:$/a\  port: 8081' /opt/flink/conf/config.yaml; \
    # The Kyuubi Flink SQL engine runs lakehouse queries in an embedded local
    # cluster (the standalone flink-jobmanager lacks the Iceberg/Ozone jars), in
    # batch mode (Iceberg tables are bounded), and logs in with the kyuubi keytab
    # so it can reach Kerberized Ozone (the engine then doAs the session user).
    # table.catalog-store points Flink at a file store so every session lazily
    # loads the `iceberg` catalog (unlike Spark, Flink registers no catalog on its
    # own) — `SELECT ... FROM iceberg.<schema>.<table>` then works with no DDL.
    printf '\nexecution:\n  target: local\n  runtime-mode: batch\ntable:\n  catalog-store:\n    kind: file\n    file:\n      path: /opt/flink/catalog-store\nsecurity:\n  kerberos:\n    login:\n      keytab: /shared/kyuubi.keytab\n      principal: kyuubi/kyuubi.test.local@TEST.LOCAL\n      use-ticket-cache: false\n' >> /opt/flink/conf/config.yaml; \
    mkdir -p /opt/flink/catalog-store; \
    cp /tmp/flink-iceberg-catalog.yaml /opt/flink/catalog-store/iceberg.yaml; \
    rm -f /tmp/flink-iceberg-catalog.yaml; \
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
