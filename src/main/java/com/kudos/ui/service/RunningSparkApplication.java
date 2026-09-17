/*
 * Copyright 2026 Aleksey Martynov and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kudos.ui.service;

import java.time.Instant;

/**
 * A Spark application that announced itself while running, as the KUDOS Spark
 * plugin reported it.
 *
 * @param sessionId the KUDOS Kyuubi session that started this engine, when it
 *     was started by one; it replaces that session's placeholder row in Jobs.
 * @param registeredAtMs when this application announced itself, for the expiry
 *     that covers a driver killed without a goodbye.
 */
public record RunningSparkApplication(
    String appId,
    String name,
    String user,
    String uiUrl,
    long startTimeMs,
    String sparkVersion,
    String sessionId,
    long registeredAtMs) {

  /** The shape the Jobs screen already renders. */
  public SparkApplication toSparkApplication() {
    return new SparkApplication(
        appId,
        name,
        user,
        Instant.ofEpochMilli(startTimeMs).toString(),
        "",
        Math.max(0, System.currentTimeMillis() - startTimeMs),
        false,
        sparkVersion,
        true);
  }
}
