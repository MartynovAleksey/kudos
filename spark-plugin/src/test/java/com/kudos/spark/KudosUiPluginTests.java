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
package com.kudos.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.apache.spark.SparkConf;
import org.junit.jupiter.api.Test;

/** Exercises the wire format and the guard rails without starting a Spark application. */
class KudosUiPluginTests {

  private record Received(String method, String path, String authorization, String body) {}

  @Test
  void registrationCarriesTheFieldsAndTheBearerToken() throws Exception {
    BlockingQueue<Received> received = new ArrayBlockingQueue<>(4);
    HttpServer server = serve(received, 200);
    try {
      String base = "http://localhost:" + server.getAddress().getPort();
      Map<String, String> body = new LinkedHashMap<>();
      body.put("appId", "app-1");
      body.put("name", "select \"1\"");
      body.put("uiUrl", "http://kyuubi.test.local:4041");
      body.put("sessionId", null);

      KudosUiPlugin.send("POST", base + KudosUiPlugin.PATH, "secret", KudosUiPlugin.json(body));

      Received registration = received.take();
      assertEquals("POST", registration.method());
      assertEquals(KudosUiPlugin.PATH, registration.path());
      assertEquals("Bearer secret", registration.authorization());
      assertEquals(
          "{\"appId\":\"app-1\",\"name\":\"select \\\"1\\\"\","
              + "\"uiUrl\":\"http://kyuubi.test.local:4041\"}",
          registration.body());

      KudosUiPlugin.send("DELETE", base + KudosUiPlugin.PATH + "/app-1", "secret", null);
      Received removal = received.take();
      assertEquals("DELETE", removal.method());
      assertEquals(KudosUiPlugin.PATH + "/app-1", removal.path());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aRefusedRegistrationIsReportedToTheCaller() throws Exception {
    BlockingQueue<Received> received = new ArrayBlockingQueue<>(1);
    HttpServer server = serve(received, 401);
    try {
      String url = "http://localhost:" + server.getAddress().getPort() + KudosUiPlugin.PATH;
      assertThrows(
          IllegalStateException.class, () -> KudosUiPlugin.send("POST", url, "wrong", "{}"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void blankSettingsReadAsAbsentSoNothingIsSent() {
    SparkConf conf = new SparkConf(false).set(KudosUiPlugin.URL_KEY, "   ");
    assertNull(KudosUiPlugin.setting(conf, KudosUiPlugin.URL_KEY));
    assertNull(KudosUiPlugin.setting(conf, KudosUiPlugin.TOKEN_KEY));
    assertEquals(
        "http://kudos",
        KudosUiPlugin.setting(conf.set(KudosUiPlugin.URL_KEY, " http://kudos "), KudosUiPlugin.URL_KEY));
  }

  @Test
  void controlCharactersCannotBreakTheJsonBody() {
    assertTrue(KudosUiPlugin.json(Map.of("name", "a\nb")).contains("\"a\\nb\""));
  }

  private static HttpServer serve(BlockingQueue<Received> received, int status) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        (HttpExchange exchange) -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  body));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return server;
  }
}
