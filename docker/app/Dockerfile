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

# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN echo 'Downloading Maven dependencies (this can take several minutes on the first build)...' && \
    mvn -B -Dstyle.color=never -DskipTests dependency:go-offline
COPY src src
RUN echo 'Compiling kudos...' && \
    mvn -B -Dstyle.color=never -DskipTests package
# Scan scripts live in the build stage so the optional scan stages below inherit
# them; copied after packaging so editing a script does not bust the build cache.
COPY scripts scripts

# =============================================================================
# Optional security-scan stages. They branch off `build` and are NOT part of the
# default image graph, so a normal `docker build` / `docker compose build`
# (which targets the final `runtime` stage) never runs them. Build one on demand
# with --target and export its Markdown report with --output. BuildKit required.
#
#   OWASP dependency-check  ->  ./owasp.md
#     DOCKER_BUILDKIT=1 docker build -f docker/app/Dockerfile \
#       --target owasp-report \
#       --secret id=owasp_key,src=$HOME/OWASP-API.key \
#       --output type=local,dest=. .
#
#   Trivy (scans the built fat-jar)  ->  ./trivy.md
#     DOCKER_BUILDKIT=1 docker build -f docker/app/Dockerfile \
#       --target trivy-report \
#       --output type=local,dest=. .
# =============================================================================

# --- OWASP dependency-check ---------------------------------------------------
# The NVD API key is provided as a BuildKit secret (a file), mounted only for the
# scan command and never written into a layer or the build history.
FROM build AS owasp-scan
RUN apt-get update && apt-get install -y --no-install-recommends python3 && \
    rm -rf /var/lib/apt/lists/*
# The NVD database and the plugin jars are cached under ~/.m2, so re-runs are
# fast and an interrupted first download resumes instead of restarting.
RUN --mount=type=secret,id=owasp_key,required=false \
    --mount=type=cache,target=/root/.m2 \
    OWASP_API_KEY_FILE=/run/secrets/owasp_key bash scripts/scan-owasp.sh

# Export-only stage: its filesystem is just the report, so
# `--output type=local` drops owasp.md straight into the destination directory.
FROM scratch AS owasp-report
COPY --from=owasp-scan /workspace/owasp.md /owasp.md

# --- Trivy --------------------------------------------------------------------
FROM build AS trivy-scan
# Bring the trivy binary in from its official image; add python3 for rendering.
COPY --from=aquasec/trivy:latest /usr/local/bin/trivy /usr/local/bin/trivy
RUN apt-get update && apt-get install -y --no-install-recommends python3 && \
    rm -rf /var/lib/apt/lists/*
# Cache Trivy's vulnerability DB so re-runs skip the ~100 MB download.
RUN --mount=type=cache,target=/root/.cache/trivy bash scripts/scan-trivy.sh

FROM scratch AS trivy-report
COPY --from=trivy-scan /workspace/trivy.md /trivy.md

# --- Runtime image (default target; must stay last) ---------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/target/kudos-0.1.0.jar app.jar
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
