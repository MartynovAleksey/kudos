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
import com.fasterxml.jackson.databind.node.NullNode;
import com.kudos.ui.config.ClusterProperties;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Minimal HBase REST transport authenticated with the signed-in user's SPNEGO ticket. */
@Service
public class HbaseRestClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;
  private final AuthorizationFactory authorizationFactory;

  @Autowired
  public HbaseRestClient(ClusterProperties properties, KerberosExecutor kerberos) {
    this(properties, kerberos, HbaseRestClient::kerberosAuthorization);
  }

  HbaseRestClient(
      ClusterProperties properties,
      KerberosExecutor kerberos,
      AuthorizationFactory authorizationFactory) {
    this.properties = properties;
    this.kerberos = kerberos;
    this.authorizationFactory = authorizationFactory;
  }

  public Response get(String path) throws Exception {
    return exchange("GET", path, null);
  }

  public Response post(String path, JsonNode body) throws Exception {
    return exchange("POST", path, body);
  }

  public Response put(String path, JsonNode body) throws Exception {
    return exchange("PUT", path, body);
  }

  public Response delete(String path) throws Exception {
    return exchange("DELETE", path, null);
  }

  public String scannerPath(String location) {
    URI scanner = URI.create(location);
    if (!scanner.isAbsolute()) {
      return location.startsWith("/") ? location : "/" + location;
    }
    URI base = baseUri();
    if (!sameOrigin(base, scanner)) {
      throw new IllegalArgumentException("HBase REST returned a scanner outside its configured URL");
    }
    return scanner.getRawPath()
        + (scanner.getRawQuery() == null ? "" : "?" + scanner.getRawQuery());
  }

  public static String pathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private Response exchange(String method, String path, JsonNode body) throws Exception {
    URI target = target(path);
    return kerberos.asLoggedInUser(
        () -> {
          HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
          connection.setRequestMethod(method);
          connection.setConnectTimeout(5_000);
          connection.setReadTimeout(30_000);
          connection.setRequestProperty("Accept", "application/json");
          connection.setRequestProperty("Authorization", authorizationFactory.create(target.toURL()));
          if (body != null) {
            byte[] payload = MAPPER.writeValueAsBytes(body);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
              output.write(payload);
            }
          }
          int status = connection.getResponseCode();
          try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            byte[] payload = stream == null ? new byte[0] : stream.readAllBytes();
            if (status < 200 || status >= 300) {
              throw new HbaseRestException(status, new String(payload, StandardCharsets.UTF_8));
            }
            JsonNode response = payload.length == 0 ? NullNode.getInstance() : MAPPER.readTree(payload);
            return new Response(status, connection.getHeaderFields(), response);
          } finally {
            connection.disconnect();
          }
        });
  }

  private URI target(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("HBase REST path is required");
    }
    String base = baseUri().toString().replaceAll("/+$", "");
    return URI.create(base + (path.startsWith("/") ? path : "/" + path));
  }

  private URI baseUri() {
    String value = properties.hbaseRestUrl();
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("HBase REST URL is not configured (set HBASE_REST_URL)");
    }
    URI uri = URI.create(value);
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalStateException("HBase REST URL must be an absolute HTTP URL");
    }
    return uri;
  }

  private static boolean sameOrigin(URI first, URI second) {
    return first.getScheme().equalsIgnoreCase(second.getScheme())
        && first.getHost().equalsIgnoreCase(second.getHost())
        && effectivePort(first) == effectivePort(second);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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

  @FunctionalInterface
  interface AuthorizationFactory {
    String create(URL target) throws Exception;
  }

  public record Response(int status, Map<String, List<String>> headers, JsonNode body) {
    public String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
          .findFirst()
          .map(Map.Entry::getValue)
          .filter(values -> !values.isEmpty())
          .map(values -> values.get(0))
          .orElse(null);
    }
  }

  public static final class HbaseRestException extends IllegalStateException {
    private final int status;

    HbaseRestException(int status, String body) {
      super("HBase REST returned " + status + (body.isBlank() ? "" : ": " + body));
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
