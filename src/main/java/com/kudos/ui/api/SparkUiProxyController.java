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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.kudos.ui.service.SparkApplicationAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the Spark History Server's own web UI from this application, so it is
 * reached through the same session and origin as the rest of the screens
 * instead of as a separate unauthenticated site on port 18080.
 *
 * <p>No rewriting of the proxied HTML is needed: Spark builds every link
 * against the {@code X-Forwarded-Context} header, which is set below.
 */
@Controller
@RequestMapping(SparkUiProxyController.PREFIX)
public class SparkUiProxyController {

  static final String PREFIX = "/spark-ui";
  private static final Pattern HISTORY_APPLICATION = Pattern.compile("^/history/([^/]+)/.*");
  private static final Pattern API_APPLICATION = Pattern.compile("^/api/v1/applications/([^/]+)(?:/.*)?");

  /**
   * Response headers worth passing through; the rest are per-hop noise.
   *
   * <p>Content-Length is deliberately absent. HttpURLConnection negotiates gzip
   * on its own and decompresses transparently, so upstream's length describes
   * the compressed body while the stream yields the decompressed one, and the
   * response is cut short. Letting the container size the body avoids that.
   */
  private static final List<String> FORWARDED_HEADERS =
      List.of("Content-Type", "Content-Disposition", "Cache-Control");

  private final ClusterProperties properties;
  private final SparkApplicationAccessService sparkAccess;

  public SparkUiProxyController(ClusterProperties properties, SparkApplicationAccessService sparkAccess) {
    this.properties = properties;
    this.sparkAccess = sparkAccess;
  }

  @GetMapping("/**")
  void proxy(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws Exception {
    String path = request.getRequestURI().substring(PREFIX.length());
    if (path.isEmpty()) {
      path = "/";
    }
    if (!sparkAccess.isAdministrator(authentication) && !isStaticResource(path)) {
      String applicationId = applicationId(path);
      if (applicationId == null || !sparkAccess.canView(authentication, applicationId)) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
        return;
      }
    }
    String query = request.getQueryString();
    URL target =
        URI.create(properties.sparkHistoryUrl() + path + (query == null ? "" : "?" + query))
            .toURL();

    HttpURLConnection connection = open(target);
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(60_000);
    // Redirects are passed back to the browser so it stays on this origin.
    connection.setInstanceFollowRedirects(false);
    // Tells Spark to prefix every link it renders with this application's path.
    connection.setRequestProperty("X-Forwarded-Context", PREFIX);
    // Ask for an uncompressed body so what is streamed on matches what the
    // headers describe.
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
      response.setHeader("Location", rewriteLocation(location));
    }

    try (InputStream body =
            status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        OutputStream out = response.getOutputStream()) {
      if (body != null) {
        body.transferTo(out);
      }
    }
  }

  /**
   * Keeps a redirect inside the proxy. Spark answers a directory URL with an
   * absolute Location; followed as-is the browser would leave for port 18080.
   */
  private String rewriteLocation(String location) {
    String base = properties.sparkHistoryUrl();
    if (location.startsWith(base)) {
      return PREFIX + location.substring(base.length());
    }
    if (location.startsWith("/") && !location.startsWith(PREFIX)) {
      return PREFIX + location;
    }
    return location;
  }

  private static String applicationId(String path) {
    for (Pattern pattern : List.of(HISTORY_APPLICATION, API_APPLICATION)) {
      Matcher matcher = pattern.matcher(path);
      if (matcher.matches()) {
        return matcher.group(1);
      }
    }
    return null;
  }

  /** Spark's CSS, JavaScript and images carry no job data but are required for a user's job page. */
  private static boolean isStaticResource(String path) {
    return path.startsWith("/static/");
  }

  /** Package-visible seam keeps proxy authorization testable without a network listener. */
  HttpURLConnection open(URL target) throws IOException {
    return (HttpURLConnection) target.openConnection();
  }
}
