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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The pages are assembled from a Thymeleaf layout fragment, which fails at
 * render time rather than at compile time. These checks keep a broken fragment
 * reference from reaching a running container.
 */
@SpringBootTest(
    properties = {
      "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM"
    })
@AutoConfigureMockMvc
class UiPageRenderingTests {

  @Autowired MockMvc mockMvc;

  @Test
  void loginPageRendersWithoutAuthentication() throws Exception {
    mockMvc
        .perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign In")));
  }

  @Test
  void anonymousUserIsSentToTheLoginPage() throws Exception {
    mockMvc.perform(get("/editor")).andExpect(status().is3xxRedirection());
  }

  @Test
  void anonymousApiCallGetsABasicChallengeNotARedirect() throws Exception {
    // Scripted clients rely on /api answering with a 401 Basic challenge; a
    // redirect to the login page would break them.
    mockMvc
        .perform(get("/api/hbase/tables"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
  }

  @Test
  @WithMockUser("admin")
  void editorRenders() throws Exception {
    assertPageRenders(
        "/editor",
        "Kyuubi Spark SQL",
        "executeQuery",
        "clearResults",
        "closeAllSessions",
        "resultsPane",
        "logsPane");
  }

  @Test
  @WithMockUser("admin")
  void editorKeepsMonitoringAboveTheQueryAndLogsInsideTheResultTabs() throws Exception {
    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    assertThat(page.indexOf("id=\"sessionMonitor\"")).isLessThan(page.indexOf("id=\"queryField\""));
    assertThat(page.indexOf("id=\"sessionMonitorOperations\""))
        .isLessThan(page.indexOf("id=\"queryField\""));
    assertThat(page.indexOf("resultsContainer"))
        .isLessThan(page.indexOf("id=\"sessionMonitorLogs\""));
    assertThat(page)
        .contains("id=\"resultsTabItem\" class=\"active\"")
        .contains("id=\"resultsPane\" class=\"tab-pane active\" role=\"tabpanel\"")
        .contains("aria-labelledby=\"logsTab\" aria-hidden=\"true\" hidden")
        .contains("class=\"actions k8s-query-actions\"");
  }

  @Test
  @WithMockUser("admin")
  void exportsDisplayedQueryResult() throws Exception {
    mockMvc
        .perform(
            post("/api/sql/export/results")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"columns\":[\"answer\"],\"rows\":[[42]]}"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .contentType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"query-results.xlsx\""));
  }

  @Test
  @WithMockUser("admin")
  void fileBrowserRenders() throws Exception {
    assertPageRenders("/filebrowser", "HDFS", "breadcrumbs");
  }

  @Test
  @WithMockUser("admin")
  void ozoneBrowserRenders() throws Exception {
    assertPageRenders("/ozone", "Ozone", "breadcrumbs");
  }

  @Test
  @WithMockUser("admin")
  void hbaseBrowserRenders() throws Exception {
    assertPageRenders("/hbase", "HBase", "tableList");
  }

  @Test
  @WithMockUser("admin")
  void jobsBrowserRenders() throws Exception {
    assertPageRenders("/jobs", "Spark Jobs", "jobList", "jobPager", "jobRange");
  }

  @Test
  @WithMockUser("some.analyst")
  void jobsFilterStartsOnTheSignedInUser() throws Exception {
    mockMvc
        .perform(get("/jobs"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-default-user=\"some.analyst\"")))
        // Last week is the range the screen opens on.
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("value=\"7\" selected=\"selected\"")));
  }

  @Test
  @WithMockUser("admin")
  void jobDetailEmbedsTheProxiedSparkUi() throws Exception {
    assertPageRenders(
        "/jobs/local-1234567890",
        "/spark-ui/history/local-1234567890/jobs/",
        "/spark-ui/api/v1/applications/local-1234567890/logs");
  }

  private void assertPageRenders(String path, String... expected) throws Exception {
    var result =
        mockMvc
            .perform(get(path))
            .andExpect(status().isOk())
            // The shared chrome has to survive fragment composition.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("sidebar-body")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("page-content")));
    for (String fragment : expected) {
      result.andExpect(content().string(org.hamcrest.Matchers.containsString(fragment)));
    }
  }
}
