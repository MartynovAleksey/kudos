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

# Pinned stable multi-architecture image; the unversioned fedora-42 tag does not exist.
FROM freeipa/freeipa-server:rocky-9-4.13.1
COPY bootstrap-test-identities /usr/local/sbin/bootstrap-test-identities
COPY bootstrap-test-identities.service /usr/lib/systemd/system/bootstrap-test-identities.service
RUN chmod 0755 /usr/local/sbin/bootstrap-test-identities && \
    mkdir -p /usr/lib/systemd/system/container-ipa.target.wants && \
    ln -s \
      ../bootstrap-test-identities.service \
      /usr/lib/systemd/system/container-ipa.target.wants/bootstrap-test-identities.service
