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

# Full Apache Gravitino server for the KUDOS test stand.
#
# We run the *full* server (not the standalone Iceberg REST server) so the
# Iceberg REST catalog runs as an auxiliary service under Gravitino's
# authorization — making the shared :9001/iceberg endpoint the metadata gate for
# the lakehouse. The endpoint and warehouse are unchanged (s3://warehouse/iceberg
# on Ozone via the S3 Gateway), so no engine needs reconfiguring.

# The full server does not ship a SQLite driver; the standalone image does, and
# we keep the same SQLite catalog DB so the demo tables survive the migration.
FROM apache/gravitino-iceberg-rest:1.3.0 AS irc

FROM apache/gravitino:1.3.0

USER root

# SQLite driver for the Iceberg catalog backend (jdbc:sqlite:…). Both the
# lakehouse-iceberg catalog and the aux Iceberg REST server load it from their
# own libs dir, so place it in both.
COPY --from=irc /opt/gravitino-iceberg-rest-server/libs/sqlite-jdbc-3.42.0.0.jar \
     /opt/gravitino/catalogs/lakehouse-iceberg/libs/sqlite-jdbc-3.42.0.0.jar
COPY --from=irc /opt/gravitino-iceberg-rest-server/libs/sqlite-jdbc-3.42.0.0.jar \
     /opt/gravitino/iceberg-rest-server/libs/sqlite-jdbc-3.42.0.0.jar

# The S3 warehouse needs the shaded AWS SDK. The image ships it under
# iceberg-bundles/ but not on the catalog classpath, so S3FileIO fails with
# NoClassDefFoundError (software.amazon.awssdk…). Place the bundle where both the
# lakehouse-iceberg catalog and the aux Iceberg REST server load their jars.
RUN cp /opt/gravitino/iceberg-bundles/gravitino-iceberg-aws-bundle-1.3.0.jar \
       /opt/gravitino/catalogs/lakehouse-iceberg/libs/ \
    && cp /opt/gravitino/iceberg-bundles/gravitino-iceberg-aws-bundle-1.3.0.jar \
       /opt/gravitino/iceberg-rest-server/libs/

# curl + jq drive the metalake/catalog bootstrap in start-gravitino.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*

COPY gravitino/gravitino.conf /opt/gravitino/conf/gravitino.conf
COPY gravitino/start-gravitino /usr/local/bin/start-gravitino

RUN chmod 0755 /usr/local/bin/start-gravitino \
    && chown 1000:0 /opt/gravitino/conf/gravitino.conf \
    && mkdir -p /opt/gravitino/data /opt/gravitino/logs \
    && chown -R 1000:0 /opt/gravitino/data /opt/gravitino/logs

USER 1000
ENTRYPOINT ["/usr/local/bin/start-gravitino"]
