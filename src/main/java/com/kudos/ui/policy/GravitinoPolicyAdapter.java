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

package com.kudos.ui.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Gravitino REST adapter. It never mirrors policy data into KUDOS or Ranger. */
@Component
public class GravitinoPolicyAdapter implements PolicyAdapter {
  private static final Set<String> PRIVILEGES = Set.of(
      "USE_CATALOG", "USE_SCHEMA", "SELECT_TABLE", "INSERT_TABLE", "UPDATE_TABLE",
      "DELETE_TABLE", "ALTER_TABLE", "CREATE_TABLE", "DROP_TABLE");
  private final URI base;
  private final String metalake;
  private final HttpClient client;
  private final ObjectMapper mapper;

  @Autowired
  public GravitinoPolicyAdapter(
      @Value("${kudos.cluster.gravitino-rest-uri:${GRAVITINO_REST_URI:http://localhost:8090}}") String uri,
      @Value("${kudos.cluster.gravitino-metalake:${GRAVITINO_METALAKE:metalake_demo}}") String metalake) {
    this.base = URI.create(uri.replaceAll("/$", ""));
    this.metalake = metalake;
    this.client = HttpClient.newHttpClient();
    this.mapper = new ObjectMapper();
  }

  // Package-visible constructor keeps adapter tests independent of a live Gravitino.
  GravitinoPolicyAdapter(String uri, String metalake, HttpClient client, ObjectMapper mapper) {
    this.base = URI.create(uri.replaceAll("/$", ""));
    this.metalake = metalake;
    this.client = client;
    this.mapper = mapper;
  }

  @Override public String tool() { return "gravitino"; }

  @Override
  public List<PolicyModels.Policy> list() {
    JsonNode root = request("GET", path("/roles"), null);
    List<PolicyModels.Policy> result = new ArrayList<>();
    JsonNode roles = root.has("names") ? root.get("names") : root.has("roles") ? root.get("roles") : root;
    if (!roles.isArray()) return result;
    for (JsonNode roleEntry : roles) {
      JsonNode role = roleEntry.isTextual()
          ? unwrapRole(request("GET", path("/roles/" + segment(roleEntry.asText())), null))
          : unwrapRole(roleEntry);
      String roleName = text(role, "name");
      JsonNode objects = role.get("securableObjects");
      if (objects == null || !objects.isArray()) continue;
      for (JsonNode object : objects) {
        String resource = text(object, "fullName");
        JsonNode privileges = object.get("privileges");
        if (privileges == null || !privileges.isArray()) continue;
        List<String> actions = new ArrayList<>();
        for (JsonNode privilege : privileges) {
          if ("ALLOW".equalsIgnoreCase(text(privilege, "condition"))) {
            actions.add(text(privilege, "name"));
          }
        }
        if (!actions.isEmpty()) {
          result.add(new PolicyModels.Policy(tool(), resource, "role:" + roleName, actions,
              "ALLOW", "Gravitino"));
        }
      }
    }
    return result;
  }

  @Override public void grant(PolicyModels.Mutation mutation) { mutate(mutation, "grant"); }
  @Override public void revoke(PolicyModels.Mutation mutation) { mutate(mutation, "revoke"); }

  private void mutate(PolicyModels.Mutation mutation, String operation) {
    validate(mutation);
    if (mutation.subject().startsWith("user:")) {
      String user = mutation.subject().substring("user:".length());
      request("PUT", path("/permissions/users/" + segment(user) + "/" + operation),
          "{\"roleNames\":[\"" + json(mutation.role()) + "\"]}");
      return;
    }
    // Gravitino stores privileges on roles; the role update is native and atomic.
    JsonNode role = unwrapRole(request("GET", path("/roles/" + segment(mutation.role())), null));
    JsonNode objects = role.withArray("securableObjects");
    JsonNode target = null;
    for (JsonNode object : objects) {
      if (mutation.resource().equals(text(object, "fullName"))) { target = object; break; }
    }
    if (target == null) {
      target = mapper.createObjectNode().put("fullName", mutation.resource())
          .put("type", resourceType(mutation.resource()));
      ((com.fasterxml.jackson.databind.node.ArrayNode) objects).add(target);
    }
    com.fasterxml.jackson.databind.node.ArrayNode privileges = target.withArray("privileges");
    if ("grant".equals(operation)) {
      privileges.add(mapper.createObjectNode().put("name", mutation.privilege()).put("condition", "ALLOW"));
    } else {
      for (int i = privileges.size() - 1; i >= 0; i--) {
        if (mutation.privilege().equals(text(privileges.get(i), "name"))) privileges.remove(i);
      }
    }
    request("PUT", path("/roles/" + segment(mutation.role())), json(role));
  }

  private void validate(PolicyModels.Mutation m) {
    if (m == null || !Set.of("grant", "revoke").contains(m.operation().toLowerCase())
        || blank(m.role()) || blank(m.subject()) || blank(m.resource()) || blank(m.privilege())
        || !PRIVILEGES.contains(m.privilege())) {
      throw new IllegalArgumentException("Unsupported or incomplete Gravitino policy mutation");
    }
    if (!m.subject().startsWith("user:") && !m.subject().startsWith("role:")) {
      throw new IllegalArgumentException("Subject must be user:<name> or role:<name>");
    }
  }

  private JsonNode request(String method, String path, String body) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
          .header("Accept", "application/vnd.gravitino.v1+json");
      if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
      else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
      HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Gravitino policy request failed: HTTP " + response.statusCode());
      }
      return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Gravitino policy request interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Gravitino policy service unavailable", e);
    }
  }

  private String path(String suffix) { return "/api/metalakes/" + segment(metalake) + suffix; }
  private static JsonNode unwrapRole(JsonNode root) {
    return root != null && root.has("role") && root.get("role").isObject() ? root.get("role") : root;
  }
  private String json(JsonNode node) { try { return mapper.writeValueAsString(node); } catch (IOException e) { throw new IllegalStateException("Invalid policy payload", e); } }
  private static String segment(String value) { return value.replaceAll("[^A-Za-z0-9._~-]", ""); }
  private static String text(JsonNode node, String field) { return node == null || node.get(field) == null ? "" : node.get(field).asText(); }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static String resourceType(String resource) { return resource.split("\\.").length >= 3 ? "TABLE" : resource.split("\\.").length == 2 ? "SCHEMA" : "CATALOG"; }
  private String json(String value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("Invalid policy value", e); } }
}
