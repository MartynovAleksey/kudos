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

package com.kudos.ui.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Answers 404 for any screen or API path whose module is disabled, so a turned
 * off service is genuinely absent rather than merely hidden.
 */
public class FeatureGate implements HandlerInterceptor {

  private final FeaturesProperties features;

  public FeatureGate(FeaturesProperties features) {
    this.features = features;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    Boolean enabled = enabledFor(request.getRequestURI());
    if (enabled != null && !enabled) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return false;
    }
    return true;
  }

  /** The module gating a path, or {@code null} when the path is not module-specific. */
  private Boolean enabledFor(String path) {
    if (path.equals("/ui-api") || path.startsWith("/ui-api/")) {
      path = "/api" + path.substring("/ui-api".length());
    }
    if (path.equals("/editor")
        || path.startsWith("/api/sql")
        || path.startsWith("/api/sessions")) {
      return features.editor();
    }
    if (path.equals("/filebrowser") || path.startsWith("/api/hdfs")) {
      return features.files();
    }
    if (path.equals("/ozone") || path.startsWith("/api/ozone")) {
      return features.ozone();
    }
    if (path.equals("/hbase") || path.startsWith("/api/hbase")) {
      return features.hbase();
    }
    if (path.equals("/jobs")
        || path.startsWith("/jobs/")
        || path.startsWith("/api/spark")
        || path.startsWith("/spark-ui")) {
      return features.jobs();
    }
    return null;
  }
}
