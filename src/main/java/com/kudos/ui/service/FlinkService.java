/*
 * Copyright 2026 Aleksey Martynov and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kudos.ui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kudos.ui.config.ClusterProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Lists live Flink jobs from the JobManager REST API and flattens them into the
 * same {@link SparkApplication} record the Jobs screen already renders, so they
 * appear in its Running section next to the live Kyuubi engines.
 *
 * <p>The source is best-effort: if no JobManager is configured or it cannot be
 * reached, the Jobs screen still works, it just shows no Flink jobs. Completed
 * Flink jobs are viewed through the History Server proxy, not from here.
 */
@Service
public class FlinkService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Non-terminal Flink job states; terminal ones belong to the History Server. */
  private static final Set<String> RUNNING_STATES =
      Set.of("RUNNING", "CREATED", "RESTARTING", "RECONCILING", "INITIALIZING");

  private final ClusterProperties properties;

  public FlinkService(ClusterProperties properties) {
    this.properties = properties;
  }

  /** Live Flink jobs from the JobManager, or empty when it is absent or unreachable. */
  public List<SparkApplication> runningApplications() {
    return jobs(properties.flinkJobmanagerUrl(), true);
  }

  /**
   * Finished Flink jobs from the History Server, or empty when it is absent or
   * unreachable. These are the durable, archived jobs the History UI serves.
   */
  public List<SparkApplication> completedApplications() {
    return jobs(properties.flinkHistoryUrl(), false);
  }

  /**
   * Maps a Flink {@code /jobs/overview} feed to applications. When {@code running}
   * is set, only non-terminal jobs are kept (the JobManager source); otherwise
   * only terminal jobs are kept (the History Server source), so a job that just
   * finished never appears in both sections.
   */
  private List<SparkApplication> jobs(String base, boolean running) {
    if (base == null || base.isBlank()) {
      return List.of();
    }
    try {
      JsonNode jobs = MAPPER.readTree(read(base + "/jobs/overview")).path("jobs");
      List<SparkApplication> applications = new ArrayList<>();
      for (JsonNode job : jobs) {
        boolean live = RUNNING_STATES.contains(job.path("state").asText());
        if (live != running) {
          continue;
        }
        long endTime = job.path("end-time").asLong();
        applications.add(
            new SparkApplication(
                "flink-" + job.path("jid").asText(),
                job.path("name").asText(),
                // The Flink REST does not report a submitting user; ownership of
                // Flink jobs is not modelled yet, so it is left blank.
                "",
                Instant.ofEpochMilli(job.path("start-time").asLong()).toString(),
                running || endTime <= 0 ? "" : Instant.ofEpochMilli(endTime).toString(),
                job.path("duration").asLong(),
                !running,
                "Flink job"));
      }
      return applications;
    } catch (Exception ignored) {
      // A missing or unhealthy Flink endpoint must not break the Jobs screen.
      return List.of();
    }
  }

  private String read(String url) throws IOException {
    HttpURLConnection connection = open(URI.create(url).toURL());
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(30_000);
    if (connection.getResponseCode() >= 400) {
      throw new IllegalStateException("Flink JobManager returned " + connection.getResponseCode());
    }
    try (InputStream stream = connection.getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Package-visible seam keeps the REST mapping testable without a network listener. */
  HttpURLConnection open(URL target) throws IOException {
    return (HttpURLConnection) target.openConnection();
  }
}
