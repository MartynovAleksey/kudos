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
package com.kudos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kudos.ui.audit.AuditService;
import com.kudos.ui.config.ApiProperties;
import com.kudos.ui.service.HbaseService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM")
@AutoConfigureMockMvc
@ContextConfiguration(initializers = ApiTestEnvironment.Enabled.class)
class EnabledApiCompatibilityTests {

  @Autowired MockMvc mockMvc;
  @Autowired ApiProperties api;

  @MockitoBean HbaseService hbase;
  @MockitoBean AuditService audit;
  @MockitoBean(name = "ldapAuthenticationManager") AuthenticationManager authenticationManager;

  @Test
  void externalApiWorksWhenEnvironmentFlagIsTrue() throws Exception {
    assertThat(api.enabled()).isTrue();
    given(authenticationManager.authenticate(any()))
        .willAnswer(
            invocation ->
                UsernamePasswordAuthenticationToken.authenticated(
                    invocation.<org.springframework.security.core.Authentication>getArgument(0)
                        .getName(),
                    "n/a",
                    List.of()));
    given(hbase.tables()).willReturn(List.of());

    mockMvc
        .perform(get("/api/hbase/tables").with(httpBasic("admin", "password")))
        .andExpect(status().isOk());

    verify(hbase).tables();
  }

  @Test
  void uiRemainsFunctionalWhenExternalApiIsEnabled() throws Exception {
    MockHttpSession session = session();
    given(hbase.tables()).willReturn(List.of());

    for (String path : List.of("/editor", "/filebrowser", "/ozone", "/hbase", "/jobs")) {
      mockMvc.perform(get(path).session(session)).andExpect(status().isOk());
    }
    mockMvc
        .perform(get("/ui-api/hbase/tables").session(session))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void openApiContainsOnlyExternalPaths() throws Exception {
    String document =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(document).contains("\"/api/").doesNotContain("/ui-api/");
  }

  @Test
  void bothControllerPrefixesAreAuditedWithTheirActualPaths() throws Exception {
    reset(audit, hbase);
    given(hbase.tables()).willReturn(List.of());
    MockHttpSession session = session();

    mockMvc.perform(get("/api/hbase/tables").session(session)).andExpect(status().isOk());
    mockMvc.perform(get("/ui-api/hbase/tables").session(session)).andExpect(status().isOk());

    verify(audit).record("GET", "/api/hbase/tables", 200);
    verify(audit).record("GET", "/ui-api/hbase/tables", 200);
  }

  @Test
  void customHbaseAdminOperationsAreNotExposed() throws Exception {
    MockHttpSession session = session();

    for (String path :
        List.of(
            "/ui-api/hbase/table/enable",
            "/ui-api/hbase/table/disable",
            "/ui-api/hbase/table/truncate",
            "/ui-api/hbase/family/delete")) {
      mockMvc
          .perform(post(path).session(session).with(csrf()))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  void browserCodeContainsNoExternalApiRequestUrls() throws Exception {
    String script =
        new ClassPathResource("app-static/kudos.js")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(script).contains("var UI_API = '/ui-api';").doesNotContain("'/api", "\"/api");
  }

  @Test
  void browserCatalogUsesStatusFromCatalogTreeResponse() throws Exception {
    String script =
        new ClassPathResource("app-static/kudos.js")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(script)
        .contains("tree.status === 'unavailable'")
        .doesNotContain("tree.status === 'unavailable' || !tree.available");
  }

  private static MockHttpSession session() {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("admin", "n/a", List.of()));
    var session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    return session;
  }
}
