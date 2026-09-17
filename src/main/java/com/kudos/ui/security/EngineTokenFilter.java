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
package com.kudos.ui.security;

import com.kudos.ui.config.SparkRegistrationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the {@code /engine-api} transport with the shared registration token.
 *
 * <p>A Spark engine has no LDAP identity and no browser session, so it presents
 * a bearer token instead. With no token configured the transport does not exist
 * at all: the path answers 404 rather than advertising itself with a 401.
 */
public class EngineTokenFilter extends OncePerRequestFilter {

  private final SparkRegistrationProperties properties;

  public EngineTokenFilter(SparkRegistrationProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!properties.enabled()) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    String header = request.getHeader("Authorization");
    String presented = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
    if (!MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8),
        properties.registrationToken().getBytes(StandardCharsets.UTF_8))) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(request, response);
  }
}
