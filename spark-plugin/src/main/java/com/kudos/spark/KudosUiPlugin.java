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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.plugin.DriverPlugin;
import org.apache.spark.api.plugin.ExecutorPlugin;
import org.apache.spark.api.plugin.PluginContext;
import org.apache.spark.api.plugin.SparkPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells KUDOS where this application's Spark UI is listening, so a running job
 * can be opened from the Jobs screen instead of only after it lands in the
 * history server.
 *
 * <p>Enable it on any Spark 3.x application by putting this jar on the driver's
 * classpath and setting:
 *
 * <pre>
 *   spark.plugins=com.kudos.spark.KudosUiPlugin
 *   spark.ui.enabled=true
 *   spark.kudos.url=https://kudos.example.org
 *   spark.kudos.token=&lt;the registration token KUDOS is configured with&gt;
 * </pre>
 *
 * <p>Nothing here is allowed to fail an application: a missing setting, an
 * unreachable KUDOS or a rejected token is logged and forgotten.
 */
public class KudosUiPlugin implements SparkPlugin {

  static final String URL_KEY = "spark.kudos.url";
  static final String TOKEN_KEY = "spark.kudos.token";
  /** Ties the engine back to the KUDOS session that asked Kyuubi to start it. */
  static final String SESSION_KEY = "spark.kudos.sessionId";
  static final String PATH = "/engine-api/spark/running";

  private static final Logger LOG = LoggerFactory.getLogger(KudosUiPlugin.class);

  @Override
  public DriverPlugin driverPlugin() {
    return new Driver();
  }

  /** Only the driver knows the UI address; executors have nothing to report. */
  @Override
  public ExecutorPlugin executorPlugin() {
    return null;
  }

  static final class Driver implements DriverPlugin {

    private SparkContext context;
    private String appId;

    @Override
    public Map<String, String> init(SparkContext context, PluginContext pluginContext) {
      this.context = context;
      return Collections.emptyMap();
    }

    /**
     * Registration happens here rather than in {@link #init}: this callback is
     * the first one Spark makes with the application id assigned, and by then
     * the UI is bound and {@code uiWebUrl} reports the port it actually got
     * (4040 is only a starting point — a second engine on the same host lands
     * on 4041).
     */
    @Override
    public void registerMetrics(String appId, PluginContext pluginContext) {
      this.appId = appId;
      SparkConf conf = context.getConf();
      String base = setting(conf, URL_KEY);
      String token = setting(conf, TOKEN_KEY);
      String uiUrl = context.uiWebUrl().isDefined() ? context.uiWebUrl().get() : null;
      if (base == null || token == null) {
        LOG.debug("KUDOS registration skipped: {} or {} is not set", URL_KEY, TOKEN_KEY);
        return;
      }
      if (uiUrl == null) {
        LOG.info("KUDOS registration skipped: the Spark UI is disabled for this application");
        return;
      }
      Map<String, String> body = new LinkedHashMap<>();
      body.put("appId", appId);
      body.put("name", context.appName());
      body.put("user", context.sparkUser());
      body.put("uiUrl", uiUrl);
      body.put("startTimeMs", Long.toString(context.startTime()));
      body.put("sparkVersion", context.version());
      body.put("sessionId", setting(conf, SESSION_KEY));
      try {
        send("POST", base + PATH, token, json(body));
        LOG.info("Registered {} with KUDOS at {}", appId, uiUrl);
      } catch (Exception failure) {
        LOG.warn("Could not register {} with KUDOS: {}", appId, failure.toString());
      }
    }

    @Override
    public void shutdown() {
      SparkConf conf = context == null ? null : context.getConf();
      String base = conf == null ? null : setting(conf, URL_KEY);
      String token = conf == null ? null : setting(conf, TOKEN_KEY);
      if (base == null || token == null || appId == null) {
        return;
      }
      try {
        send(
            "DELETE",
            base + PATH + "/" + URLEncoder.encode(appId, StandardCharsets.UTF_8),
            token,
            null);
      } catch (Exception failure) {
        // The entry also expires on its own, so a lost goodbye is not worth a stack trace.
        LOG.warn("Could not unregister {} from KUDOS: {}", appId, failure.toString());
      }
    }
  }

  static String setting(SparkConf conf, String key) {
    String value = conf.get(key, "").trim();
    return value.isEmpty() ? null : value;
  }

  /**
   * Called on the driver's startup and shutdown paths, so the timeouts are
   * short: a KUDOS that does not answer must cost seconds, not minutes.
   */
  static int send(String method, String url, String token, String body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(2_000);
    connection.setReadTimeout(3_000);
    connection.setRequestProperty("Authorization", "Bearer " + token);
    if (body != null) {
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(bytes);
      }
    }
    int status = connection.getResponseCode();
    connection.disconnect();
    if (status >= 400) {
      throw new IllegalStateException("KUDOS answered " + status);
    }
    return status;
  }

  /** A handful of flat string fields; a JSON library on the driver's classpath is not worth it. */
  static String json(Map<String, String> fields) {
    StringBuilder json = new StringBuilder("{");
    for (Map.Entry<String, String> field : fields.entrySet()) {
      if (field.getValue() == null) {
        continue;
      }
      if (json.length() > 1) {
        json.append(',');
      }
      json.append(quote(field.getKey())).append(':').append(quote(field.getValue()));
    }
    return json.append('}').toString();
  }

  private static String quote(String value) {
    StringBuilder quoted = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      switch (character) {
        case '"' -> quoted.append("\\\"");
        case '\\' -> quoted.append("\\\\");
        case '\n' -> quoted.append("\\n");
        case '\r' -> quoted.append("\\r");
        case '\t' -> quoted.append("\\t");
        default -> {
          if (character < 0x20) {
            quoted.append(String.format("\\u%04x", (int) character));
          } else {
            quoted.append(character);
          }
        }
      }
    }
    return quoted.append('"').toString();
  }
}
