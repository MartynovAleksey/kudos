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

package com.kudos.ui;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kudos.ui.service.SparkApplication;
import com.kudos.ui.service.SparkHistoryService;
import com.kudos.ui.service.KyuubiService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the actual UI API chain with distinct administrator and user sessions. */
@SpringBootTest(properties = "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM")
@AutoConfigureMockMvc
class JobsRoleIntegrationTests {

  @Autowired MockMvc mockMvc;

  @MockitoBean SparkHistoryService history;
  @MockitoBean KyuubiService kyuubi;

  @Test
  void userCannotUseAHandCraftedOwnerFilterAndCanOpenOnlyOwnJob() throws Exception {
    SparkApplication own = application("own", "analyst", false);
    SparkApplication foreign = application("foreign", "admin", true);
    when(history.applications(500, null)).thenReturn(List.of(own, foreign));
    when(history.application("own")).thenReturn(Optional.of(own));
    when(history.application("foreign")).thenReturn(Optional.of(foreign));

    mockMvc
        .perform(get("/ui-api/spark/applications").param("user", "admin").session(session("analyst", "ROLE_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("own"))
        .andExpect(jsonPath("$.length()").value(1));
    mockMvc.perform(get("/jobs/own").session(session("analyst", "ROLE_USER"))).andExpect(status().isOk());
    mockMvc
        .perform(get("/jobs/foreign").session(session("analyst", "ROLE_USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void administratorCanFilterAndOpenAnotherUsersJob() throws Exception {
    SparkApplication foreign = application("foreign", "analyst", true);
    when(history.applications(500, null)).thenReturn(List.of(foreign));

    mockMvc
        .perform(get("/ui-api/spark/applications").param("user", "analyst").session(session("admin", "ROLE_ADMINISTRATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("foreign"));
    mockMvc
        .perform(get("/jobs/foreign").session(session("admin", "ROLE_ADMINISTRATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void administratorSeesAnotherUsersLiveKyuubiEngine() throws Exception {
    SparkApplication running = application("kyuubi-analyst", "analyst", false);
    when(history.applications(500, null)).thenReturn(List.of());
    when(kyuubi.runningApplicationsForAllUsers()).thenReturn(List.of(running));

    mockMvc
        .perform(get("/ui-api/spark/applications").session(session("admin", "ROLE_ADMINISTRATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("kyuubi-analyst"))
        .andExpect(jsonPath("$[0].user").value("analyst"));
  }

  @Test
  void userSeesOwnCompletedApplication() throws Exception {
    SparkApplication completed = application("completed", "analyst", true);
    when(history.applications(500, null)).thenReturn(List.of(completed));

    mockMvc
        .perform(get("/ui-api/spark/applications").session(session("analyst", "ROLE_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("completed"))
        .andExpect(jsonPath("$[0].completed").value(true));
  }

  private static SparkApplication application(String id, String user, boolean completed) {
    return new SparkApplication(id, id, user, "2026-07-29T00:00:00", "", 0, completed, "4.0");
  }

  private static MockHttpSession session(String username, String role) {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            username, "n/a", List.of(new SimpleGrantedAuthority(role))));
    var session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    return session;
  }
}
