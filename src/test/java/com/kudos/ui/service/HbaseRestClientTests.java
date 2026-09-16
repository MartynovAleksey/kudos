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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kudos.ui.config.ClusterProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HbaseRestClientTests {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsJsonRequestWithTheLoggedInUsersSpnegoToken() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> method = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/rest/tables",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          method.set(exchange.getRequestMethod());
          byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    HbaseRestClient client = client("http://localhost:" + server.getAddress().getPort() + "/rest");
    HbaseRestClient.Response response = client.get("/tables");

    assertEquals(200, response.status());
    assertTrue(response.body().isArray());
    assertEquals("GET", method.get());
    assertEquals("Negotiate test-ticket", authorization.get());
  }

  @Test
  void preservesTheRestStatusInErrorsAndRejectsForeignScannerUrls() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/rest/denied",
        exchange -> {
          byte[] body = "forbidden".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(403, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    HbaseRestClient client = client("http://localhost:" + server.getAddress().getPort() + "/rest");
    HbaseRestClient.HbaseRestException error =
        assertThrows(HbaseRestClient.HbaseRestException.class, () -> client.get("/denied"));

    assertEquals(403, error.status());
    assertTrue(error.getMessage().contains("forbidden"));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.scannerPath("http://another-host.invalid/scanner/1"));
  }

  private static HbaseRestClient client(String restUrl) {
    ClusterProperties properties =
        new ClusterProperties("", "", "", "", "", restUrl, "", "", "", "", "", "", "", "");
    KerberosExecutor kerberos =
        new KerberosExecutor() {
          @Override
          public <T> T asLoggedInUser(PrivilegedExceptionAction<T> action) throws Exception {
            return action.run();
          }
        };
    return new HbaseRestClient(properties, kerberos, target -> "Negotiate test-ticket");
  }
}
