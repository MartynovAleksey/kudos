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

package com.kudos.ui.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kudos.ui.service.ToolVisibilityStore;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "kudos.api.enabled=true",
      "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM"
    })
@AutoConfigureMockMvc
class ToolVisibilityControllerTests {

  @Autowired MockMvc mockMvc;
  @Autowired ToolVisibilityStore store;

  @AfterEach
  void restoreDefaults() {
    // The store is a shared singleton; leave every tool visible for other tests.
    store.update(Map.of("editor", true, "files", true, "ozone", true, "hbase", true, "jobs", true));
  }

  @Test
  @WithMockUser(username = "user", authorities = "ROLE_USER")
  void anySignedInUserCanReadTheVisibility() throws Exception {
    mockMvc
        .perform(get("/ui-api/tool-visibility"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.editor").value(true));
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void anAdministratorCanHideATool() throws Exception {
    mockMvc
        .perform(
            put("/ui-api/tool-visibility")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hbase\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hbase").value(false))
        .andExpect(jsonPath("$.editor").value(true));
  }

  @Test
  @WithMockUser(username = "user", authorities = "ROLE_USER")
  void aRegularUserCannotChangeTheVisibility() throws Exception {
    mockMvc
        .perform(
            put("/ui-api/tool-visibility")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hbase\":false}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anUnauthenticatedCallIsRefused() throws Exception {
    mockMvc.perform(get("/ui-api/tool-visibility")).andExpect(status().isUnauthorized());
  }
}
