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
package com.kudos.ui.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Hides the external API and its documentation before authentication runs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAvailabilityFilter extends OncePerRequestFilter {

  private final ApiProperties api;

  public ApiAvailabilityFilter(ApiProperties api) {
    this.api = api;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!api.enabled() && isExternalApiPath(request.getRequestURI(), request.getContextPath())) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    chain.doFilter(request, response);
  }

  static boolean isExternalApiPath(String requestUri, String contextPath) {
    String path =
        contextPath == null || contextPath.isEmpty()
            ? requestUri
            : requestUri.substring(contextPath.length());
    return path.equals("/api")
        || path.startsWith("/api/")
        || path.equals("/swagger-ui")
        || path.equals("/swagger-ui.html")
        || path.startsWith("/swagger-ui/")
        || path.equals("/v3/api-docs")
        || path.equals("/v3/api-docs.yaml")
        || path.startsWith("/v3/api-docs/");
  }
}
