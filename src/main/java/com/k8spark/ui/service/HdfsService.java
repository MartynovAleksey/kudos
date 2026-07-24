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
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.KerberosAuthenticator;
import org.springframework.stereotype.Service;

@Service
public class HdfsService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;

  public HdfsService(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  public String list(String path) throws Exception {
    return kerberos.asLoggedInUser(() -> readAsString(webhdfs(path, "LISTSTATUS")));
  }

  public List<FileEntry> listEntries(String path) throws Exception {
    JsonNode statuses = MAPPER.readTree(list(path)).path("FileStatuses").path("FileStatus");
    List<FileEntry> entries = new ArrayList<>();
    for (JsonNode status : statuses) {
      String name = status.path("pathSuffix").asText();
      entries.add(
          new FileEntry(
              name,
              join(path, name),
              "DIRECTORY".equals(status.path("type").asText()),
              status.path("length").asLong(),
              status.path("owner").asText(),
              status.path("group").asText(),
              status.path("permission").asText(),
              status.path("modificationTime").asLong()));
    }
    return entries;
  }

  /** Reads the head of a file for the preview pane. */
  public String preview(String path, int maxBytes) throws Exception {
    return kerberos.asLoggedInUser(
        () -> {
          try (InputStream stream = open(webhdfs(path, "OPEN&length=" + maxBytes))) {
            return new String(stream.readNBytes(maxBytes), StandardCharsets.UTF_8);
          }
        });
  }

  private URL webhdfs(String path, String operation) throws Exception {
    StringBuilder encoded = new StringBuilder();
    for (String segment : path.split("/")) {
      if (!segment.isEmpty()) {
        encoded.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8));
      }
    }
    return new URL(properties.webhdfsUrl() + "/webhdfs/v1" + encoded + "?op=" + operation);
  }

  private static String join(String parent, String name) {
    return parent.endsWith("/") ? parent + name : parent + "/" + name;
  }

  private static InputStream open(URL url) throws Exception {
    AuthenticatedURL.Token token = new AuthenticatedURL.Token();
    HttpURLConnection connection =
        new AuthenticatedURL(new KerberosAuthenticator()).openConnection(url, token);
    connection.setRequestMethod("GET");
    if (connection.getResponseCode() >= 400) {
      throw new IllegalStateException("WebHDFS returned " + connection.getResponseCode());
    }
    return connection.getInputStream();
  }

  private static String readAsString(URL url) throws Exception {
    try (InputStream stream = open(url)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
