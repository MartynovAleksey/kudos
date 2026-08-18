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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kudos.ui.config.ClusterProperties;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.springframework.stereotype.Service;

/** Reads session telemetry from Kyuubi's REST API as the signed-in Kerberos user. */
@Service
public class KyuubiRestClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;

  public KyuubiRestClient(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  public Snapshot monitor(String sessionId) throws Exception {
    JsonNode event = get("/api/v1/sessions/" + encode(sessionId));
    JsonNode pool = get("/api/v1/sessions/execPool/statistic");
    return new Snapshot(
        event.path("engineId").asText(),
        event.path("engineName").asText(),
        event.path("engineUrl").asText(),
        event.path("openedTime").asLong(),
        event.path("totalOperations").asInt(),
        pool.path("execPoolSize").asInt(),
        pool.path("execPoolActiveCount").asInt(),
        pool.path("execPoolWorkQueueSize").asInt());
  }

  private JsonNode get(String path) throws Exception {
    if (properties.kyuubiRestUrl() == null || properties.kyuubiRestUrl().isBlank()) {
      throw new IllegalStateException("Kyuubi REST URL is not configured");
    }
    return kerberos.asLoggedInUser(
        () -> {
          String base = properties.kyuubiRestUrl().replaceAll("/+$", "");
          URL target = URI.create(base + path).toURL();
          HttpURLConnection connection = (HttpURLConnection) target.openConnection();
          connection.setRequestMethod("GET");
          connection.setConnectTimeout(5_000);
          connection.setReadTimeout(15_000);
          connection.setRequestProperty("Accept", "application/json");
          connection.setRequestProperty("Authorization", kerberosAuthorization(target));
          int status = connection.getResponseCode();
          try (InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (status >= 400) {
              String message = body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
              throw new IllegalStateException("Kyuubi REST returned " + status + ": " + message);
            }
            return MAPPER.readTree(body);
          } finally {
            connection.disconnect();
          }
        });
  }

  private static String kerberosAuthorization(URL target) throws Exception {
    GSSManager manager = GSSManager.getInstance();
    GSSName service =
        manager.createName("HTTP@" + target.getHost(), GSSName.NT_HOSTBASED_SERVICE);
    GSSContext context =
        manager.createContext(
            service, new Oid("1.2.840.113554.1.2.2"), null, GSSContext.DEFAULT_LIFETIME);
    try {
      context.requestMutualAuth(false);
      byte[] token = context.initSecContext(new byte[0], 0, 0);
      if (token == null) {
        throw new IllegalStateException("Kerberos did not produce a token for " + target.getHost());
      }
      return "Negotiate " + Base64.getEncoder().encodeToString(token);
    } finally {
      context.dispose();
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record Snapshot(
      String engineId,
      String engineName,
      String engineUrl,
      long openedAtEpochMs,
      int totalOperations,
      int executorPoolSize,
      int executorPoolActiveCount,
      int executorPoolQueueSize) {}
}
