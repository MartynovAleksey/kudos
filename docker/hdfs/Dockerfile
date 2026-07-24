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

FROM apache/hadoop:3.4.2

USER root

COPY core-site.xml hdfs-site.xml /opt/hadoop/etc/hadoop/
COPY start-hdfs /usr/local/bin/start-hdfs
RUN chmod 0755 /usr/local/bin/start-hdfs && \
    mkdir -p /data && \
    chown -R hadoop:users /data

ENV HDFS_NAMENODE_USER=root \
    HDFS_DATANODE_USER=root \
    HDFS_SECONDARYNAMENODE_USER=root

ENTRYPOINT ["/usr/local/bin/start-hdfs"]
