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

import com.kudos.ui.config.ClusterProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Reads a Spark application's engine log — the raw stdout and stderr of the
 * Kyuubi engine that ran it — back out of Ozone.
 *
 * <p>The log collector writes one directory per engine launch
 * ({@code <root>/<user>/<seq>/part-<n>.log}), so a log is several objects that
 * have to be stitched back together in index order. Nothing in the object names
 * carries the Spark application id, so an application is matched to its
 * directory by the line Spark itself prints when it opens its event log.
 */
@Service
public class EngineLogService {

  /** Spark's own startup line, the only place the application id meets the engine's output. */
  private static final String MARKER = "eventlogs/";

  private static final Pattern PART = Pattern.compile("^part-(\\d+)\\.log$");

  /** A whole engine log is held in memory to render it; stand logs are far below this. */
  private static final int MAX_LOG_BYTES = 8 * 1024 * 1024;

  private final ClusterProperties properties;
  private final OzoneService ozone;
  private final SparkHistoryService history;

  /**
   * Resolved directory per application. Only the lookup is cached, never the
   * content: a running engine keeps appending to it.
   */
  private final Map<String, String> directoryByApplication = new ConcurrentHashMap<>();

  public EngineLogService(
      ClusterProperties properties, OzoneService ozone, SparkHistoryService history) {
    this.properties = properties;
    this.ozone = ozone;
    this.history = history;
  }

  /** Blank configuration hides the Logs tab, the way a blank Flink URL hides its source. */
  public boolean enabled() {
    String root = properties.engineLogsPath();
    return root != null && !root.isBlank();
  }

  /**
   * The application's engine log, parts concatenated in index order, or an empty
   * string when nothing was collected for it.
   */
  public String log(String applicationId) throws Exception {
    if (!enabled()) {
      return "";
    }
    String known = directoryByApplication.get(applicationId);
    if (known != null) {
      return read(known);
    }
    for (String candidate : candidates(applicationId)) {
      String text = read(candidate);
      if (text.contains(MARKER + applicationId)) {
        directoryByApplication.put(applicationId, candidate);
        return text;
      }
    }
    return "";
  }

  /**
   * Engine-log directories that could belong to this application: every launch
   * by its owner.
   *
   * <p>ponytail: linear scan of one user's directories, each read once until the
   * marker is found. On this stand a user has a handful of launches. If the scan
   * stops fitting in a page response, write the resolved pairs to a small index
   * object next to the logs instead of re-reading them.
   */
  private List<String> candidates(String applicationId) throws Exception {
    Optional<String> owner = history.application(applicationId).map(SparkApplication::user);
    if (owner.isEmpty() || owner.get().isBlank()) {
      return List.of();
    }
    String userRoot = properties.engineLogsPath() + "/" + owner.get();
    try {
      return ozone.listEntries(userRoot).stream()
          .filter(FileEntry::directory)
          .map(FileEntry::path)
          // Newest launch first: a re-run of the same statement is the common lookup.
          .sorted(Comparator.reverseOrder())
          .toList();
    } catch (Exception missing) {
      // No logs collected for this user yet; an absent directory is not an error.
      return List.of();
    }
  }

  /** Concatenates {@code part-<n>.log} in numeric order, so part-10 follows part-9. */
  private String read(String directory) throws Exception {
    List<FileEntry> parts = new ArrayList<>();
    for (FileEntry entry : ozone.listEntries(directory)) {
      if (!entry.directory() && PART.matcher(entry.name()).matches()) {
        parts.add(entry);
      }
    }
    parts.sort(Comparator.comparingInt(entry -> index(entry.name())));

    StringBuilder log = new StringBuilder();
    for (FileEntry part : parts) {
      int remaining = MAX_LOG_BYTES - log.length();
      if (remaining <= 0) {
        break;
      }
      log.append(ozone.preview(part.path(), remaining));
    }
    // Checked after the loop, not only inside it: truncation can land in the very
    // last part, where the in-loop remaining<=0 branch never gets another turn to fire.
    if (log.length() >= MAX_LOG_BYTES) {
      log.append("\n... truncated at ").append(MAX_LOG_BYTES).append(" bytes ...\n");
    }
    return log.toString();
  }

  private static int index(String name) {
    Matcher matcher = PART.matcher(name);
    return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
  }
}
