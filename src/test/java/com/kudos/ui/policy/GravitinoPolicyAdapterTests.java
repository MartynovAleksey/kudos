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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GravitinoPolicyAdapterTests {
  @Test
  void rejectsUnknownPrivilegeBeforeCallingGravitino() {
    var adapter = new GravitinoPolicyAdapter("http://unused", "metalake_demo",
        HttpClient.newHttpClient(), new ObjectMapper());
    assertThrows(IllegalArgumentException.class, () -> adapter.grant(
        new PolicyModels.Mutation("grant", "reader", "user:analyst", "catalog.demo", "EXECUTE")));
  }

  @Test
  void rejectsUnqualifiedSubjectBeforeCallingGravitino() {
    var adapter = new GravitinoPolicyAdapter("http://unused", "metalake_demo",
        HttpClient.newHttpClient(), new ObjectMapper());
    assertThrows(IllegalArgumentException.class, () -> adapter.revoke(
        new PolicyModels.Mutation("revoke", "reader", "analyst", "catalog.demo", "USE_SCHEMA")));
  }

  @Test
  void listsRolesFromNamesEnvelopeAndFetchesRoleDetails() throws Exception {
    try (TestServer server = new TestServer()) {
      server.respond("/roles", "{\"code\":0,\"names\":[\"reader\"]}");
      server.respond("/roles/reader", "{\"code\":0,\"role\":{\"name\":\"reader\",\"securableObjects\":[{\"fullName\":\"catalog.demo\",\"privileges\":[{\"name\":\"USE_CATALOG\",\"condition\":\"ALLOW\"}]}]}}");

      var policies = new GravitinoPolicyAdapter(server.uri(), "metalake_demo",
          HttpClient.newHttpClient(), new ObjectMapper()).list();

      assertEquals(1, policies.size());
      assertEquals("role:reader", policies.get(0).subject());
      assertEquals("catalog.demo", policies.get(0).resource());
      assertEquals("USE_CATALOG", policies.get(0).actions().get(0));
    }
  }

  @Test
  void unwrapsRoleEnvelopeBeforeUpdatingRole() throws Exception {
    try (TestServer server = new TestServer()) {
      server.respond("/roles/reader", "{\"code\":0,\"role\":{\"name\":\"reader\",\"securableObjects\":[]}}");
      var adapter = new GravitinoPolicyAdapter(server.uri(), "metalake_demo",
          HttpClient.newHttpClient(), new ObjectMapper());

      adapter.grant(new PolicyModels.Mutation("grant", "reader", "role:reader",
          "catalog.demo", "USE_CATALOG"));

      assertEquals("PUT", server.method().get());
      String body = server.body().get();
      assertTrue(body.contains("\"name\":\"reader\""));
      assertTrue(body.contains("\"securableObjects\""));
      assertTrue(!body.contains("\"role\""));
    }
  }

  private static final class TestServer implements AutoCloseable {
    private final HttpServer server;
    private final java.util.Map<String, String> responses = new java.util.HashMap<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>("");

    TestServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/api/metalakes/metalake_demo", this::handle);
      server.start();
    }

    String uri() { return "http://localhost:" + server.getAddress().getPort(); }
    void respond(String path, String response) { responses.put(path, response); }
    AtomicReference<String> method() { return method; }
    AtomicReference<String> body() { return body; }

    private void handle(HttpExchange exchange) throws IOException {
      method.set(exchange.getRequestMethod());
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String relative = exchange.getRequestURI().getPath().replace("/api/metalakes/metalake_demo", "");
      byte[] response = responses.getOrDefault(relative, "{\"code\":0}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      try (var output = exchange.getResponseBody()) { output.write(response); }
    }

    @Override public void close() { server.stop(0); }
  }
}
