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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kudos.ui.service.HbaseService;
import com.kudos.ui.service.HbaseTableInfo;
import com.kudos.ui.service.HdfsService;
import com.kudos.ui.service.KyuubiService;
import com.kudos.ui.service.OzoneService;
import com.kudos.ui.service.QueryResult;
import com.kudos.ui.service.SparkApplication;
import com.kudos.ui.service.SparkHistoryService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM")
@AutoConfigureMockMvc
@ContextConfiguration(initializers = ApiTestEnvironment.Disabled.class)
class ApiDisabledIntegrationTests {

  @Autowired MockMvc mockMvc;

  @MockitoBean HdfsService hdfs;
  @MockitoBean KyuubiService kyuubi;
  @MockitoBean HbaseService hbase;
  @MockitoBean OzoneService ozone;
  @MockitoBean SparkHistoryService sparkHistory;

  @Test
  void externalApiIsHiddenBeforeEveryAuthenticationMode() throws Exception {
    mockMvc
        .perform(get("/api/hbase/tables"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("WWW-Authenticate"));
    mockMvc
        .perform(get("/api/hbase/tables").with(httpBasic("admin", "password")))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("WWW-Authenticate"));
    mockMvc
        .perform(get("/api/hbase/tables").session(session()))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("WWW-Authenticate"));

    verifyNoInteractions(hbase);
  }

  @Test
  void apiDocumentationIsHiddenWithTheApi() throws Exception {
    for (String path :
        List.of(
            "/swagger-ui.html",
            "/swagger-ui/index.html",
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/v3/api-docs.yaml")) {
      mockMvc
          .perform(get(path))
          .andExpect(status().isNotFound())
          .andExpect(header().doesNotExist("WWW-Authenticate"));
    }
  }

  @Test
  void uiTransportKeepsRepresentativeModulesFunctional() throws Exception {
    HttpSession session = session();
    QueryResult result = new QueryResult(List.of("answer"), List.of(List.of(1)));
    given(kyuubi.execute("SELECT 1", 1000)).willReturn(result);
    given(kyuubi.sessions()).willReturn(List.of());
    given(hdfs.listEntries("/")).willReturn(List.of());
    given(ozone.listEntries("/")).willReturn(List.of());
    given(hbase.tables()).willReturn(List.of(new HbaseTableInfo("events")));
    given(sparkHistory.applications(500, null))
        .willReturn(
            List.of(
                new SparkApplication(
                    "app-1", "demo", "admin", "start", "end", 10, true, "4.0")));

    mockMvc
        .perform(
            post("/ui-api/sql/execute")
                .session((MockHttpSession) session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"columns\":[\"answer\"],\"rows\":[[1]]}"));
    mockMvc
        .perform(
            post("/ui-api/sql/export/results")
                .session((MockHttpSession) session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"columns\":[\"answer\"],\"rows\":[[1]]}"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .contentType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    mockMvc.perform(get("/ui-api/sessions").session((MockHttpSession) session)).andExpect(status().isOk());
    mockMvc
        .perform(get("/ui-api/hdfs/list").param("path", "/").session((MockHttpSession) session))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/ui-api/ozone/list").param("path", "/").session((MockHttpSession) session))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/ui-api/hbase/tables").session((MockHttpSession) session))
        .andExpect(status().isOk())
        .andExpect(content().json("[{\"name\":\"events\"}]"));
    mockMvc
        .perform(get("/ui-api/spark/applications").session((MockHttpSession) session))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("app-1")));
  }

  @Test
  void authenticatedUiPagesStillRender() throws Exception {
    MockHttpSession session = session();
    for (String path : List.of("/editor", "/filebrowser", "/ozone", "/hbase", "/jobs")) {
      mockMvc.perform(get(path).session(session)).andExpect(status().isOk());
    }
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
