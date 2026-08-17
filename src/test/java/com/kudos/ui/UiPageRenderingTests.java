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

import com.kudos.ui.service.EngineVisibilityStore;
import com.kudos.ui.service.ToolVisibilityStore;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
      "kudos.api.enabled=true",
      "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM"
    })
@AutoConfigureMockMvc
class UiPageRenderingTests {

  @Autowired MockMvc mockMvc;
  @Autowired ToolVisibilityStore toolVisibility;
  @Autowired EngineVisibilityStore engineVisibility;

  @AfterEach
  void restoreVisibility() {
    // The stores are shared singletons; leave everything visible for other tests.
    toolVisibility.update(
        Map.of("editor", true, "files", true, "ozone", true, "hbase", true, "jobs", true));
    engineVisibility.update(
        Map.of("kyuubi", true, "kyuubi-flink", true, "trino", true, "starrocks", true));
  }

  @Test
  void loginPageRendersWithoutAuthentication() throws Exception {
    mockMvc
        .perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign In")));
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void modernChromeScrollsLongPagesInTheContentPane() throws Exception {
    mockMvc
        .perform(get("/static/app/kudos.css"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "body[data-ui-mode=\"modern\"] .hue-page {\n"
                            + "  height: 100vh;\n"
                            + "  border: 0;\n"
                            + "  border-radius: 0;\n"
                            + "  background: var(--k8s-window);\n"
                            + "  overflow: hidden;")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "body[data-ui-mode=\"modern\"] .main-page {\n"
                            + "  height: 100vh;\n"
                            + "  min-height: 0;\n"
                            + "  margin-left: 252px;\n"
                            + "  padding: 26px 30px;\n"
                            + "  box-sizing: border-box;\n"
                            + "  background: var(--k8s-window);\n"
                            + "  overflow-y: auto;")));
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
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void editorRenders() throws Exception {
    assertPageRenders(
        "/editor",
        "Kyuubi Spark SQL",
        "Kyuubi Flink SQL",
        "Trino",
        "StarRocks",
        "class=\"nav editor-nav k8s-jobs-nav\"",
        "class=\"k8s-titlebar-tab active\"",
        "data-sql-engine=\"kyuubi\"",
        "data-sql-engine=\"kyuubi-flink\"",
        "data-sql-engine=\"trino\"",
        "data-sql-engine=\"starrocks\"",
        "data-supports-history=\"true\"",
        "executeQuery",
        "clearResults",
        "closeAllSessions",
        "name=\"_csrf\"",
        "name=\"_csrf_header\"",
        "resultsPane",
        "logsPane");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void sharedChromeKeepsTheToolOrderAndProvidesUserInterfaceSettings() throws Exception {
    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    assertThat(page)
        .contains("data-ui-mode=\"modern\"")
        .contains("data-ui-theme=\"system\"")
        .contains("data-ui-user=\"admin\"")
        .contains("/static/app/brand/logo-mark.png")
        .contains("id=\"uiSettingsPanel\"")
        .contains("data-ui-theme-choice=\"system\"")
        .contains("id=\"uiSettingsReset\"");
    // The old/modern toggle has been removed; the interface is always modern.
    assertThat(page).doesNotContain("data-ui-mode-choice");
    assertToolOrder(page, "editor", "files", "ozone", "hbase", "jobs");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void administratorGetsTheVisibleToolsSettingAndSidebarCollapse() throws Exception {
    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    assertThat(page)
        .contains("Visible tools")
        .contains("data-ui-tool-choice=\"editor\"")
        .contains("id=\"uiSidebarCollapse\"")
        .contains("data-ui-sidebar=\"collapsed\"");
  }

  @Test
  @WithMockUser(username = "user", authorities = "ROLE_USER")
  void aRegularUserHasNoVisibleToolsSetting() throws Exception {
    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    // The setting is administrator-only, but the tools themselves still render.
    assertThat(page).doesNotContain("Visible tools", "data-ui-tool-choice=");
    assertThat(page).contains("data-ui-tool=\"editor\"");
  }

  @Test
  @WithMockUser(username = "user", authorities = "ROLE_USER")
  void aGloballyHiddenToolIsOmittedForARegularUser() throws Exception {
    toolVisibility.update(Map.of("hbase", false));

    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    assertThat(page).doesNotContain("data-ui-tool=\"hbase\"");
    assertThat(page).contains("data-ui-tool=\"editor\"");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void administratorGetsTheSqlEngineToggles() throws Exception {
    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    assertThat(page)
        .contains("SQL engines")
        .contains("data-ui-engine-choice=\"trino\"")
        .contains("data-ui-engine-choice=\"kyuubi-flink\"");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void aHiddenEngineHasNoEditorTab() throws Exception {
    engineVisibility.update(Map.of("trino", false));

    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    assertThat(page).doesNotContain("data-sql-engine=\"trino\"");
    assertThat(page).contains("data-sql-engine=\"kyuubi\"");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void editorAndJobsKeepTheirExistingTabAnchors() throws Exception {
    String editor = mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
        .getContentAsString();
    String jobs = mockMvc.perform(get("/jobs")).andExpect(status().isOk()).andReturn().getResponse()
        .getContentAsString();

    assertThat(editor).contains("id=\"sessionBar\"", "id=\"resultsTab\"", "id=\"logsTab\"", "id=\"operationsTab\"");
    assertThat(jobs).contains("id=\"sparkJobsTab\"", "id=\"flinkJobsTab\"", "id=\"runningJobs\"", "id=\"flinkRunning\"");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void editorKeepsMonitoringAboveTheQueryAndOutputInsideSeparateTabs() throws Exception {
    String page =
        mockMvc.perform(get("/editor")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();

    assertThat(page.indexOf("id=\"sessionMonitor\"")).isLessThan(page.indexOf("id=\"queryField\""));
    assertThat(page.indexOf("resultsContainer"))
        .isLessThan(page.indexOf("id=\"sessionMonitorLogs\""));
    assertThat(page.indexOf("resultsContainer"))
        .isLessThan(page.indexOf("id=\"sessionMonitorOperations\""));
    assertThat(page)
        .contains("id=\"resultsTabItem\" class=\"active\"")
        .contains("id=\"resultsPane\" class=\"tab-pane active\" role=\"tabpanel\"")
        .contains("aria-labelledby=\"logsTab\" aria-hidden=\"true\" hidden")
        .contains("aria-labelledby=\"operationsTab\" aria-hidden=\"true\" hidden")
        .contains("class=\"actions k8s-query-actions\"");
    assertThat(page).doesNotContain("engineSelect");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
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
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void fileBrowserRenders() throws Exception {
    assertPageRenders("/filebrowser", "HDFS", "breadcrumbs");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void ozoneBrowserRenders() throws Exception {
    assertPageRenders("/ozone", "Ozone", "breadcrumbs");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void hbaseBrowserRenders() throws Exception {
    assertPageRenders("/hbase", "HBase", "tableList");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void jobsBrowserRenders() throws Exception {
    assertPageRenders("/jobs", "flinkJobsTab", "jobList", "jobPager", "jobRange");
  }

  @Test
  @WithMockUser("some.analyst")
  void userCannotSeeTheAdministratorUserFilter() throws Exception {
    mockMvc
        .perform(get("/jobs"))
        .andExpect(status().isOk())
        .andExpect(
                content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jobUserFilter"))))
        // Last week is the range the screen opens on.
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("value=\"7\" selected=\"selected\"")));
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void jobDetailEmbedsTheProxiedSparkUi() throws Exception {
    assertPageRenders(
        "/jobs/local-1234567890",
        "/spark-ui/history/local-1234567890/jobs/",
        "/spark-ui/api/v1/applications/local-1234567890/logs");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void flinkJobEmbedsTheProxiedUiInTheKudosChrome() throws Exception {
    assertPageRenders(
        "/flink/jobmanager/abc123", "/flink-ui/jobmanager/?embedded#/job/running/abc123/overview");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void runningKyuubiFlinkEngineEmbedsTheJobManagerInTheKudosChrome() throws Exception {
    assertPageRenders("/flink/jobmanager", "/flink-ui/jobmanager/?embedded");
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void completedFlinkJobDeepLinksToTheHistoryRoute() throws Exception {
    assertPageRenders(
        "/flink/history/abc123", "/flink-ui/history/?embedded#/job/completed/abc123/overview");
  }

  @Test
  @WithMockUser("some.analyst")
  void userCannotOpenAFlinkJob() throws Exception {
    mockMvc.perform(get("/flink/history/abc123")).andExpect(status().isForbidden());
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

  private static void assertToolOrder(String page, String... tools) {
    int previous = -1;
    for (String tool : tools) {
      int index = page.indexOf("data-ui-tool=\"" + tool + "\"");
      assertThat(index).isGreaterThan(previous);
      previous = index;
    }
  }
}
