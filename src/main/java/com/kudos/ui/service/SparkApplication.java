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

/**
 * One Spark application as the history server reports it, flattened to the
 * attempt the jobs screen shows.
 *
 * @param live whether a running application announced its own UI and can
 *     therefore be opened while it runs; a placeholder row for an engine that
 *     has not registered yet has no UI to link to.
 */
public record SparkApplication(
    String id,
    String name,
    String user,
    String startTime,
    String endTime,
    long durationMillis,
    boolean completed,
    String sparkVersion,
    boolean live) {

  /** Most sources describe applications that are either finished or not openable yet. */
  public SparkApplication(
      String id,
      String name,
      String user,
      String startTime,
      String endTime,
      long durationMillis,
      boolean completed,
      String sparkVersion) {
    this(id, name, user, startTime, endTime, durationMillis, completed, sparkVersion, false);
  }
}
