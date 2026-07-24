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

FROM gethue/hue:latest

USER root

# The Docker Hub image contains the regular x86_64 Polars wheel, which uses
# CPU instructions unavailable to amd64 containers translated by Rosetta on
# Apple Silicon. The LTS wheel has the same Python API and changes no Hue UI.
RUN /usr/share/hue/build/env/bin/pip uninstall -y polars \
    && /usr/share/hue/build/env/bin/pip install --no-cache-dir polars-lts-cpu==1.8.2
