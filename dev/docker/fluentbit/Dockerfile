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

# The -debug variant, not the default image: Ozone mints the S3 secret at runtime
# and publishes it to a shared volume, so the entrypoint has to source a file
# before exec'ing Fluent Bit. The default image is distroless and carries no
# shell at all, at build time or at run time.
FROM fluent/fluent-bit:3.2-debug

COPY fluent-bit.conf /fluent-bit/etc/fluent-bit.conf
# Copied with its executable bit; chmod would need a shell the image may not have.
COPY start-log-collector /usr/local/bin/start-log-collector

ENTRYPOINT ["/usr/local/bin/start-log-collector"]
