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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Builds the Editor catalog tree from Gravitino's Iceberg REST catalog: the
 * shared {@code iceberg} catalog, its namespaces (schemas) and their tables.
 *
 * <p>The call runs as the signed-in user (like every other cluster call, via
 * {@link KerberosExecutor}) so that once an authorization layer is in place the
 * tree is naturally scoped to what that user may see. A catalog that cannot be
 * reached yields an {@code unavailable} tree with a diagnostic rather than an
 * error, so the Editor keeps working.
 */
@Service
public class CatalogTreeService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  /** The single logical catalog every engine shares (Iceberg REST). */
  private static final String CATALOG = "iceberg";

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;

  public CatalogTreeService(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  public CatalogTree tree() {
    String base = properties.gravitinoIcebergUri();
    if (base == null || base.isBlank()) {
      return CatalogTree.unavailable(CATALOG, "Iceberg catalog is not configured");
    }
    try {
      return kerberos.asLoggedInUser(() -> fetch(base.replaceAll("/+$", "")));
    } catch (ForbiddenException e) {
      return CatalogTree.forbidden(CATALOG);
    } catch (Exception e) {
      return CatalogTree.unavailable(CATALOG, "GRAVITINO_UNAVAILABLE");
    }
  }

  private CatalogTree fetch(String base) throws Exception {
    List<CatalogTree.Schema> schemas = new ArrayList<>();
    JsonNode namespaces = get(base + "/v1/namespaces");
    for (JsonNode ns : namespaces.path("namespaces")) {
      // The stand uses single-level namespaces; join levels with a dot for display.
      String name = joinLevels(ns);
      List<String> tables = new ArrayList<>();
      JsonNode identifiers = get(base + "/v1/namespaces/" + encode(name) + "/tables");
      if (identifiers != null) {
        for (JsonNode id : identifiers.path("identifiers")) {
          tables.add(id.path("name").asText());
        }
      }
      schemas.add(new CatalogTree.Schema(name, List.copyOf(tables)));
    }
    return CatalogTree.available(CATALOG, schemas);
  }

  /**
   * GETs a Gravitino Iceberg REST resource as the signed-in user. The user is
   * carried in an HTTP Basic header (Gravitino simple auth reads the identity
   * from it; the password is ignored), so the metadata gate scopes the response
   * to what Gravitino granted that user. A {@code 403} is kept distinct from an
   * empty successful response so the UI can explain the access denial.
   */
  private JsonNode get(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setRequestMethod("GET");
    connection.setRequestProperty("Accept", "application/json");
    connection.setRequestProperty("Authorization", basicAuthForCurrentUser());
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(10000);
    try {
      int code = connection.getResponseCode();
      if (code == 403) {
        throw new ForbiddenException();
      }
      if (code >= 400) {
        throw new IllegalStateException("HTTP " + code + " from " + url);
      }
      try (InputStream in = connection.getInputStream()) {
        return MAPPER.readTree(in);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static String basicAuthForCurrentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String user =
        (authentication == null || authentication.getName() == null)
            ? "anonymous"
            : authentication.getName();
    String token = Base64.getEncoder().encodeToString((user + ":x").getBytes(StandardCharsets.UTF_8));
    return "Basic " + token;
  }

  private static String joinLevels(JsonNode namespace) {
    List<String> levels = new ArrayList<>();
    namespace.forEach(level -> levels.add(level.asText()));
    return String.join(".", levels);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static final class ForbiddenException extends Exception {
    private static final long serialVersionUID = 1L;
  }
}
