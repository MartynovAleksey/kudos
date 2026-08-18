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
package com.kudos.ui.api;

import com.kudos.ui.config.ClusterProperties;
import com.kudos.ui.service.SparkApplicationAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the Flink History Server's web UI from this application, behind the same
 * session and origin as the rest of the screens. Mirrors {@link SparkUiProxyController}.
 *
 * <p>Standalone Flink jobs remain administrator-only. A user who ran a Kyuubi
 * FLINK_SQL query may open the Flink UI because KUDOS recorded its real job-id.
 *
 * <p>Known ceiling: the Flink dashboard is an Angular single-page app that, unlike
 * Spark, does not honour {@code X-Forwarded-Context}. The History Server serves
 * mostly static content, which proxies cleanly; a live JobManager UI under this
 * sub-path would additionally need its {@code config.json}/base path rewritten.
 */
@Controller
@RequestMapping(FlinkUiProxyController.PREFIX)
public class FlinkUiProxyController {

  static final String PREFIX = "/flink-ui";

  /** See SparkUiProxyController: Content-Length is dropped so gzip cannot truncate the body. */
  private static final List<String> FORWARDED_HEADERS =
      List.of("Content-Type", "Content-Disposition", "Cache-Control");

  private final ClusterProperties properties;
  private final SparkApplicationAccessService sparkAccess;

  public FlinkUiProxyController(
      ClusterProperties properties, SparkApplicationAccessService sparkAccess) {
    this.properties = properties;
    this.sparkAccess = sparkAccess;
  }

  @GetMapping("/**")
  void proxy(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws Exception {
    if (!sparkAccess.canUseFlinkUi(authentication)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    String path = request.getRequestURI().substring(PREFIX.length());
    if (path.isEmpty()) {
      path = "/";
    }
    // Running jobs live in the JobManager, finished ones in the History Server,
    // so the first path segment selects the upstream. A bare /flink-ui/ opens the
    // History dashboard.
    String segment;
    String upstream;
    if (path.equals("/jobmanager") || path.startsWith("/jobmanager/")) {
      segment = "/jobmanager";
      upstream = properties.flinkJobmanagerUrl();
      path = path.substring(segment.length());
    } else if (path.equals("/history") || path.startsWith("/history/")) {
      segment = "/history";
      upstream = properties.flinkHistoryUrl();
      path = path.substring(segment.length());
    } else {
      segment = "/history";
      upstream = properties.flinkHistoryUrl();
    }
    if (path.isEmpty()) {
      path = "/";
    }
    String query = request.getQueryString();
    URL target = URI.create(upstream + path + (query == null ? "" : "?" + query)).toURL();

    HttpURLConnection connection = open(target);
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(60_000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("X-Forwarded-Context", PREFIX + segment);
    connection.setRequestProperty("Accept-Encoding", "identity");

    int status = connection.getResponseCode();
    response.setStatus(status);
    for (String header : FORWARDED_HEADERS) {
      String value = connection.getHeaderField(header);
      if (value != null) {
        response.setHeader(header, value);
      }
    }
    String location = connection.getHeaderField("Location");
    if (location != null) {
      response.setHeader("Location", rewriteLocation(location, upstream, segment));
    }

    // When embedded in the KUDOS chrome, hide Flink's own left sider so only its
    // main content shows. The flag is stripped into the HTML of the SPA shell;
    // it rides on the initial document request, not on assets or REST calls.
    boolean embedded = query != null && query.contains("embedded");
    String contentType = connection.getHeaderField("Content-Type");
    boolean injectStyle =
        embedded && status < 400 && contentType != null && contentType.startsWith("text/html");

    try (InputStream body =
            status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        OutputStream out = response.getOutputStream()) {
      if (body == null) {
        return;
      }
      if (injectStyle) {
        out.write(hideSider(new String(body.readAllBytes(), StandardCharsets.UTF_8))
            .getBytes(StandardCharsets.UTF_8));
      } else {
        body.transferTo(out);
      }
    }
  }

  /** The KUDOS chrome already provides navigation, so drop Flink's own sider. */
  private static final String HIDE_SIDER_STYLE =
      "<style>.ant-layout-sider{display:none!important;}</style>";

  private static String hideSider(String html) {
    int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
    return head < 0
        ? HIDE_SIDER_STYLE + html
        : html.substring(0, head) + HIDE_SIDER_STYLE + html.substring(head);
  }

  /** Keeps a redirect inside the proxy instead of leaking the upstream origin. */
  private String rewriteLocation(String location, String upstream, String segment) {
    if (location.startsWith(upstream)) {
      return PREFIX + segment + location.substring(upstream.length());
    }
    if (location.startsWith("/") && !location.startsWith(PREFIX)) {
      return PREFIX + segment + location;
    }
    return location;
  }

  /** Package-visible seam keeps proxy authorization testable without a network listener. */
  HttpURLConnection open(URL target) throws IOException {
    return (HttpURLConnection) target.openConnection();
  }
}
