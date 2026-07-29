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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The filter is the guarantee that a session cannot outlive the ticket it was
 * built on, so these checks pin the behaviour a browser and a script each see
 * once that ticket has lapsed.
 */
class TicketExpiryFilterTests {

  private final TicketExpiryFilter filter = new TicketExpiryFilter();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private void authenticateWith(Instant expiresAt) {
    var authentication = new KerberosAuthentication("admin", null, expiresAt, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void anExpiredTicketRedirectsAPageToTheLoginWithExplanation() throws Exception {
    authenticateWith(Instant.now().minusSeconds(1));
    var request = new MockHttpServletRequest("GET", "/editor");
    request.setRequestURI("/editor");
    request.getSession(true);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?expired");
    // The request never reached the page controller.
    assertThat(chain.getRequest()).isNull();
    // The session is gone.
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void anExpiredTicketRefusesAnApiCallWithoutAskingForBasic() throws Exception {
    authenticateWith(Instant.now().minusSeconds(1));
    var request = new MockHttpServletRequest("GET", "/api/hdfs/list");
    request.setRequestURI("/api/hdfs/list");
    request.getSession(true);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    // No native Basic box for a browser whose ticket merely aged out.
    assertThat(response.getHeader("WWW-Authenticate")).isNull();
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void anExpiredTicketRefusesAUiApiCallWithoutRedirecting() throws Exception {
    authenticateWith(Instant.now().minusSeconds(1));
    var request = new MockHttpServletRequest("GET", "/ui-api/sessions");
    request.setRequestURI("/ui-api/sessions");
    request.getSession(true);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getRedirectedUrl()).isNull();
    assertThat(response.getHeader("WWW-Authenticate")).isNull();
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void aValidTicketPassesThrough() throws Exception {
    authenticateWith(Instant.now().plusSeconds(3600));
    var request = new MockHttpServletRequest("GET", "/editor");
    request.setRequestURI("/editor");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    // The page controller is reached and no redirect is issued.
    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(response.getRedirectedUrl()).isNull();
  }
}
