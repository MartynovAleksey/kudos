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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kudos.ui.config.ClusterProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedExceptionAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CatalogTreeServiceTests {

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static KerberosExecutor passthroughKerberos() {
    return new KerberosExecutor() {
      @Override
      public <T> T asLoggedInUser(PrivilegedExceptionAction<T> action) throws Exception {
        return action.run();
      }
    };
  }

  private CatalogTreeService service(String gravitinoUri) {
    ClusterProperties properties =
        new ClusterProperties(
            "", "", "", "", "", "", "", "", "", "", "", "", gravitinoUri, "");
    return new CatalogTreeService(properties, passthroughKerberos());
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  void treeListsSchemasAndTables() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/v1/namespaces",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          if (path.endsWith("/tables")) {
            reply(
                exchange,
                "{\"identifiers\":[{\"namespace\":[\"demo\"],\"name\":\"customers\"},"
                    + "{\"namespace\":[\"demo\"],\"name\":\"orders\"}]}");
          } else {
            reply(exchange, "{\"namespaces\":[[\"demo\"]]}");
          }
        });
    server.start();

    CatalogTree tree = service("http://localhost:" + server.getAddress().getPort() + "/").tree();

    assertTrue(tree.available());
    assertEquals("available", tree.status());
    assertEquals("iceberg", tree.catalog());
    assertEquals(1, tree.schemas().size());
    assertEquals("demo", tree.schemas().get(0).name());
    assertEquals(java.util.List.of("customers", "orders"), tree.schemas().get(0).tables());
  }

  @Test
  void forbiddenUserIsDistinguishedFromEmptyTree() throws Exception {
    // Gravitino denies metadata deny-by-default: an ungranted user gets 403 on
    // listNamespaces. The API must retain the forbidden state for diagnostics.
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/v1/namespaces",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();

    CatalogTree tree = service("http://localhost:" + server.getAddress().getPort() + "/").tree();

    assertFalse(tree.available());
    assertEquals("forbidden", tree.status());
    assertTrue(tree.schemas().isEmpty());
    assertEquals("CATALOG_ACCESS_DENIED", tree.error());
  }

  @Test
  void successfulEmptyCatalogIsDistinctFromForbidden() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/v1/namespaces",
        exchange -> reply(exchange, "{\"namespaces\":[]}"));
    server.start();

    CatalogTree tree = service("http://localhost:" + server.getAddress().getPort() + "/").tree();

    assertTrue(tree.available());
    assertEquals("available", tree.status());
    assertTrue(tree.schemas().isEmpty());
  }

  @Test
  void blankUriYieldsUnavailableTreeNotAnError() {
    CatalogTree tree = service("").tree();
    assertFalse(tree.available());
    assertEquals("unavailable", tree.status());
    assertTrue(tree.schemas().isEmpty());
    assertNotNull(tree.error());
  }

  @Test
  void unreachableCatalogYieldsUnavailableTreeNotAnError() {
    // Port 1 is not listening: the fetch fails, but the Editor must still load.
    CatalogTree tree = service("http://localhost:1/iceberg/").tree();
    assertFalse(tree.available());
    assertEquals("unavailable", tree.status());
    assertTrue(tree.schemas().isEmpty());
    assertNotNull(tree.error());
  }
}
