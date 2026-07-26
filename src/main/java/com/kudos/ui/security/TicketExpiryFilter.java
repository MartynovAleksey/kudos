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

package com.kudos.ui.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session the moment the Kerberos ticket it was built on expires. The
 * session is deliberately capped at the ticket's lifetime — past it the ticket
 * is refused by every service, so keeping the session alive would only present
 * a signed-in user who can do nothing.
 *
 * <p>The browser counts the same deadline down and signs out on its own, but a
 * throttled or reopened tab may miss it; this server-side check is the one that
 * actually holds the guarantee.
 */
public class TicketExpiryFilter extends OncePerRequestFilter {

  private final LogoutHandler ticketCleanup = new KerberosTicketCleanup();
  private final LogoutHandler sessionCleanup = new SecurityContextLogoutHandler();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof KerberosAuthentication kerberos
        && kerberos.expiresAt() != null
        && !Instant.now().isBefore(kerberos.expiresAt())) {
      ticketCleanup.logout(request, response, authentication);
      // Invalidates the session and clears the context, so the request below
      // proceeds as an anonymous one.
      sessionCleanup.logout(request, response, authentication);

      String uri = request.getRequestURI();
      if (uri.startsWith("/api/")) {
        // No WWW-Authenticate: the browser must not answer an expired ticket
        // with a native Basic box. The script client reads the 401 and stops.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"session expired\"}");
        return;
      }
      if (isPublic(uri)) {
        // The login page and its assets render for the now-anonymous request.
        chain.doFilter(request, response);
        return;
      }
      response.sendRedirect(request.getContextPath() + "/login?expired");
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean isPublic(String uri) {
    return uri.equals("/login")
        || uri.equals("/logout")
        || uri.equals("/error")
        || uri.equals("/actuator/health")
        || uri.startsWith("/static/");
  }
}
