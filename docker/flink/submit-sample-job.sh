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

# Submits a long-running Flink example job so the Jobs "Running" section and the
# /flink-ui proxy have something to show. The TopSpeedWindowing streaming example
# runs until cancelled, which keeps it in RUNNING state for the demo.
#
# Usage (from the repo root, with the stend up):
#   ./docker/flink/submit-sample-job.sh
set -euo pipefail

docker compose exec -T flink-jobmanager \
  flink run --detached \
  /opt/flink/examples/streaming/TopSpeedWindowing.jar

echo "Submitted TopSpeedWindowing. Check running jobs:"
echo "  curl -s http://localhost:8081/jobs/overview"
