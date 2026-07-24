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

FROM apache/kyuubi:1.10.1-spark

USER root

ENV JAVA_TOOL_OPTIONS=-Djava.security.krb5.conf=/shared/krb5.conf

COPY kyuubi-defaults.conf /opt/kyuubi/conf/kyuubi-defaults.conf
COPY core-site.xml hive-site.xml /opt/kyuubi/conf/
COPY start-kerberos-kyuubi /opt/kyuubi/bin/start-kerberos-kyuubi
RUN chmod 0755 /opt/kyuubi/bin/start-kerberos-kyuubi && \
    chown -R 10009:root /opt/kyuubi/conf

USER 10009
ENTRYPOINT ["/opt/kyuubi/bin/start-kerberos-kyuubi"]
