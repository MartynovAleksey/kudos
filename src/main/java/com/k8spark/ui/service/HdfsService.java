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
import java.io.OutputStream;
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

  // ------------------------------------------------------------- write ops

  public void mkdirs(String path) throws Exception {
    kerberos.asLoggedInUser(
        () -> {
          writeOp(webhdfs(path, "MKDIRS"), "PUT");
          return null;
        });
  }

  public void delete(String path, boolean recursive) throws Exception {
    kerberos.asLoggedInUser(
        () -> {
          writeOp(webhdfs(path, "DELETE&recursive=" + recursive), "DELETE");
          return null;
        });
  }

  public void rename(String path, String destination) throws Exception {
    kerberos.asLoggedInUser(
        () -> {
          String dest = URLEncoder.encode(destination, StandardCharsets.UTF_8);
          writeOp(webhdfs(path, "RENAME&destination=" + dest), "PUT");
          return null;
        });
  }

  public void setPermission(String path, String permission) throws Exception {
    kerberos.asLoggedInUser(
        () -> {
          writeOp(webhdfs(path, "SETPERMISSION&permission=" + permission), "PUT");
          return null;
        });
  }

  public void setOwner(String path, String owner, String group) throws Exception {
    kerberos.asLoggedInUser(
        () -> {
          StringBuilder op = new StringBuilder("SETOWNER");
          if (owner != null && !owner.isBlank()) {
            op.append("&owner=").append(URLEncoder.encode(owner, StandardCharsets.UTF_8));
          }
          if (group != null && !group.isBlank()) {
            op.append("&group=").append(URLEncoder.encode(group, StandardCharsets.UTF_8));
          }
          writeOp(webhdfs(path, op.toString()), "PUT");
          return null;
        });
  }

  /** Creates or overwrites a file, following WebHDFS's 307 redirect to a datanode. */
  public void upload(String path, byte[] data) throws Exception {
    kerberos.asLoggedInUser(
        () -> {
          AuthenticatedURL.Token token = new AuthenticatedURL.Token();
          HttpURLConnection namenode =
              new AuthenticatedURL(new KerberosAuthenticator())
                  .openConnection(webhdfs(path, "CREATE&overwrite=true"), token);
          namenode.setRequestMethod("PUT");
          namenode.setInstanceFollowRedirects(false);
          int redirect = namenode.getResponseCode();
          String location = namenode.getHeaderField("Location");
          if (redirect != 307 || location == null) {
            throw new IllegalStateException("WebHDFS CREATE expected a 307 redirect, got " + redirect);
          }
          HttpURLConnection datanode =
              new AuthenticatedURL(new KerberosAuthenticator())
                  .openConnection(new URL(location), token);
          datanode.setRequestMethod("PUT");
          datanode.setDoOutput(true);
          datanode.setRequestProperty("Content-Type", "application/octet-stream");
          try (OutputStream out = datanode.getOutputStream()) {
            out.write(data);
          }
          if (datanode.getResponseCode() >= 400) {
            throw new IllegalStateException(
                "WebHDFS upload to the datanode returned " + datanode.getResponseCode());
          }
          return null;
        });
  }

  /** Streams a file to {@code out} for download (WebHDFS OPEN follows to a datanode). */
  public void download(String path, OutputStream out) throws Exception {
    kerberos.asLoggedInUser(
        () -> {
          try (InputStream in = open(webhdfs(path, "OPEN"))) {
            in.transferTo(out);
          }
          return null;
        });
  }

  private static void writeOp(URL url, String method) throws Exception {
    AuthenticatedURL.Token token = new AuthenticatedURL.Token();
    HttpURLConnection connection =
        new AuthenticatedURL(new KerberosAuthenticator()).openConnection(url, token);
    connection.setRequestMethod(method);
    if (connection.getResponseCode() >= 400) {
      String body;
      try (InputStream error = connection.getErrorStream()) {
        body = error == null ? "" : new String(error.readAllBytes(), StandardCharsets.UTF_8);
      }
      throw new IllegalStateException(
          "WebHDFS " + method + " returned " + connection.getResponseCode() + ": " + body);
    }
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
