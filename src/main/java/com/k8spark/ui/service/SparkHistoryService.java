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

package com.k8spark.ui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.k8spark.ui.config.ClusterProperties;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads finished Spark applications from the history server's REST API. The
 * engines Kyuubi starts are short-lived, so the history server is the only
 * place their jobs remain visible.
 */
@Service
public class SparkHistoryService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ClusterProperties properties;

  public SparkHistoryService(ClusterProperties properties) {
    this.properties = properties;
  }

  /**
   * @param minDate earliest application start to return, as {@code yyyy-MM-dd}
   *     or an ISO instant; {@code null} for no lower bound. The history server
   *     applies it, so a narrow range is never transferred and then discarded.
   */
  public List<SparkApplication> applications(int limit, String minDate) throws Exception {
    StringBuilder query =
        new StringBuilder(properties.sparkHistoryUrl())
            .append("/api/v1/applications?limit=")
            .append(limit);
    if (minDate != null && !minDate.isBlank()) {
      query.append("&minDate=").append(URLEncoder.encode(minDate, StandardCharsets.UTF_8));
    }
    URL url = new URL(query.toString());
    JsonNode root = MAPPER.readTree(read(url));
    List<SparkApplication> applications = new ArrayList<>();
    for (JsonNode application : root) {
      // The REST API reports one entry per attempt, newest first. The stand
      // never retries an engine, so the first attempt is the run.
      JsonNode attempt = application.path("attempts").path(0);
      applications.add(
          new SparkApplication(
              application.path("id").asText(),
              application.path("name").asText(),
              attempt.path("sparkUser").asText(),
              attempt.path("startTime").asText(),
              attempt.path("endTime").asText(),
              attempt.path("duration").asLong(),
              attempt.path("completed").asBoolean(),
              attempt.path("appSparkVersion").asText()));
    }
    return applications;
  }

  /**
   * Plain HTTP, with no Kerberos: Spark 4 cannot load Hadoop's SPNEGO filter,
   * which is still a javax.servlet.Filter, so the history server's endpoint is
   * unauthenticated. It reads the event logs from Ozone as its own principal.
   */
  private static String read(URL url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(30_000);
    if (connection.getResponseCode() >= 400) {
      throw new IllegalStateException(
          "Spark History returned " + connection.getResponseCode());
    }
    try (InputStream stream = connection.getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
