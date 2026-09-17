#!/usr/bin/env bash
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

#
# Builds the application artifacts into ./dist and stages the Spark UI plugin
# into the Kyuubi build context. Used by both ways of running the stand:
# dev/scripts/run-test-env.sh (Compose) and dev/scripts/k8s-redeploy.sh.
#
# The runtime image (dev/docker/app/Dockerfile.runtime, distroless) is built from
# these, so no build tooling ever lands in the runtime. Uses `--target build` +
# docker cp instead of BuildKit --output so it works with the keychain-safe,
# PATH-stripped docker wrapper below (buildx is not on that PATH).
#
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/../.." && pwd)"
docker_bin="$(command -v docker)"

run_docker() {
  env DOCKER_CONFIG="$project_root/dev/docker/.docker-config" PATH="/usr/bin:/bin" "$docker_bin" "$@"
}

echo "Building application artifacts (jar + healthcheck + Spark plugin) into ./dist ..."
rm -rf "$project_root/dist"
mkdir -p "$project_root/dist"
run_docker build -f "$project_root/dev/docker/app/Dockerfile.build" \
  --target build -t kudos-test-artifacts "$project_root"
cid="$(run_docker create kudos-test-artifacts)"
trap 'run_docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT
run_docker cp "$cid:/workspace/target/kudos-0.1.0.jar" "$project_root/dist/kudos-0.1.0.jar"
run_docker cp "$cid:/workspace/healthcheck.jar" "$project_root/dist/healthcheck.jar"
run_docker cp "$cid:/workspace/spark-plugin/target/kudos-spark-plugin-0.1.0.jar" \
  "$project_root/dist/kudos-spark-plugin-0.1.0.jar"
# The Kyuubi image is built from dev/docker, so the Spark UI hook has to be
# inside that context to be copied into the engines' Spark jars/.
cp "$project_root/dist/kudos-spark-plugin-0.1.0.jar" \
  "$project_root/dev/docker/kyuubi/kudos-spark-plugin.jar"
