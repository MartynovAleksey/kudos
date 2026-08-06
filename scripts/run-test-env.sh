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

# Starts the test stack on Intel Macs and Apple Silicon Macs.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
docker_bin="$(command -v docker)"
docker_compose_bin="${DOCKER_COMPOSE_BIN:-$HOME/.docker/cli-plugins/docker-compose}"

# Docker Desktop's global config may delegate anonymous registry pulls to the
# macOS Keychain. CI and unattended sessions can fail there with -67674. This
# project-local config has no credentials and is used only for public image
# pulls/builds; it does not modify the user's Docker Desktop login state.
run_docker() {
  env DOCKER_CONFIG="$project_root/docker/.docker-config" PATH="/usr/bin:/bin" "$docker_bin" "$@"
}

run_compose() {
  env \
    DOCKER_CONFIG="$project_root/docker/.docker-config" \
    PATH="/usr/bin:/bin" \
    "$docker_compose_bin" \
    -f "$project_root/compose.yaml" \
    "$@"
}

# Build the app artifacts (fat-jar + healthcheck class) into ./dist. The runtime
# image (docker/app/Dockerfile.runtime, distroless) is built from these by compose,
# so it carries no build tooling. Uses `--target build` + docker cp instead of
# BuildKit --output so it works with the keychain-safe, PATH-stripped run_docker
# wrapper (buildx is not on that PATH).
build_artifacts() {
  echo "Building application artifacts (jar + healthcheck) into ./dist ..."
  rm -rf "$project_root/dist"
  mkdir -p "$project_root/dist"
  run_docker build -f "$project_root/docker/app/Dockerfile.build" \
    --target build -t kudos-test-artifacts "$project_root"
  local cid
  cid="$(run_docker create kudos-test-artifacts)"
  run_docker cp "$cid:/workspace/target/kudos-0.1.0.jar" "$project_root/dist/kudos-0.1.0.jar"
  run_docker cp "$cid:/workspace/healthcheck.jar" "$project_root/dist/healthcheck.jar"
  run_docker rm -f "$cid" >/dev/null
}

host_architecture="$(uname -m)"
case "$host_architecture" in
  arm64 | aarch64)
    native_architecture=arm64
    ;;
  x86_64 | amd64)
    native_architecture=amd64
    ;;
  *)
    echo "Unsupported host architecture: $host_architecture" >&2
    exit 1
    ;;
esac

if ! run_docker info >/dev/null 2>&1; then
  echo "Docker Desktop is not running or is unavailable." >&2
  exit 1
fi

# FreeIPA and app run natively; Kyuubi's Spark image is amd64-only.
# Remove only stale project images created for a different CPU before the next build.
for image in kudos-test-freeipa kudos-test-hbase kudos-test-app \
  kudos-test-spark-history; do
  architecture="$(run_docker image inspect "$image" --format '{{.Architecture}}' 2>/dev/null || true)"
  if [[ -n "$architecture" && "$architecture" != "$native_architecture" ]]; then
    echo "Removing stale $architecture image: $image"
    run_docker image rm "$image" >/dev/null
  fi
done

echo "Starting FreeIPA and app natively as linux/$native_architecture."
echo "Kyuubi runs as linux/amd64 (emulated on Apple Silicon)."
unset DOCKER_DEFAULT_PLATFORM
build_artifacts
run_compose up --build --remove-orphans "$@"
