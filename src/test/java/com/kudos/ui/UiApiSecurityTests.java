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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kudos.ui.service.HdfsService;
import com.kudos.ui.service.KyuubiService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM"
    })
@AutoConfigureMockMvc
class UiApiSecurityTests {

  @Autowired MockMvc mockMvc;

  @MockitoBean KyuubiService kyuubi;
  @MockitoBean HdfsService hdfs;

  @Test
  void uiApiAcceptsAnExistingSession() throws Exception {
    given(kyuubi.sessions()).willReturn(List.of());

    mockMvc.perform(get("/ui-api/sessions").session(session())).andExpect(status().isOk());

    verify(kyuubi).sessions();
  }

  @Test
  void anonymousAndBasicOnlyRequestsCannotUseUiApi() throws Exception {
    mockMvc
        .perform(get("/ui-api/sessions"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("WWW-Authenticate"));
    mockMvc
        .perform(get("/ui-api/sessions").with(httpBasic("admin", "password")))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("WWW-Authenticate"));

    verifyNoInteractions(kyuubi);
  }

  @Test
  void jsonMutationRequiresCsrfAndRunsWithAValidToken() throws Exception {
    MockHttpSession session = session();

    mockMvc
        .perform(
            post("/ui-api/sessions/stop")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"session-1\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(kyuubi);

    mockMvc
        .perform(
            post("/ui-api/sessions/stop")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"session-1\"}"))
        .andExpect(status().isOk());
    verify(kyuubi).stop("session-1");
  }

  @Test
  void multipartMutationRunsWithAValidCsrfToken() throws Exception {
    clearInvocations(hdfs);
    var file = new MockMultipartFile("file", "sample.txt", "text/plain", "hello".getBytes());

    mockMvc
        .perform(
            multipart("/ui-api/hdfs/upload")
                .file(file)
                .param("path", "/tmp")
                .session(session())
                .with(csrf()))
        .andExpect(status().isOk());

    verify(hdfs).upload("/tmp/sample.txt", "hello".getBytes());
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
