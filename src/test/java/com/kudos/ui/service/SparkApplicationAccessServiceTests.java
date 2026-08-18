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
package com.kudos.ui.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kudos.ui.security.RoleAccess;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SparkApplicationAccessServiceTests {

  private final SparkHistoryService history = mock(SparkHistoryService.class);
  private final KyuubiService kyuubi = mock(KyuubiService.class);
  private final KyuubiFlinkService kyuubiFlink = mock(KyuubiFlinkService.class);
  private final SqlQueryHistory sqlHistory = mock(SqlQueryHistory.class);
  private final FlinkService flink = mock(FlinkService.class);
  private final SparkApplicationAccessService access =
      new SparkApplicationAccessService(history, kyuubi, kyuubiFlink, sqlHistory, flink, new RoleAccess());

  @Test
  void userGetsOnlyOwnApplicationsEvenWhenPassingAnotherUserFilter() throws Exception {
    SparkApplication own = application("own", "analyst", false);
    SparkApplication foreign = application("foreign", "admin", true);
    when(history.applications(500, null)).thenReturn(List.of(own, foreign));

    assertThat(access.applications(user("analyst"), 500, null, "admin")).containsExactly(own);
  }

  @Test
  void administratorCanFilterApplicationsByOwner() throws Exception {
    SparkApplication own = application("own", "analyst", false);
    SparkApplication foreign = application("foreign", "admin", true);
    when(history.applications(500, null)).thenReturn(List.of(own, foreign));

    assertThat(access.applications(administrator(), 500, null, "admin")).containsExactly(foreign);
  }

  @Test
  void administratorSeesLiveKyuubiEnginesOfEveryUser() throws Exception {
    SparkApplication analyst = application("kyuubi-analyst", "analyst", false);
    SparkApplication admin = application("kyuubi-admin", "admin", false);
    when(history.applications(500, null)).thenReturn(List.of());
    when(kyuubi.runningApplicationsForAllUsers()).thenReturn(List.of(analyst, admin));

    assertThat(access.applications(administrator(), 500, null, null))
        .containsExactly(analyst, admin);
  }

  @Test
  void userSeesTheirLiveKyuubiEngineInRunning() throws Exception {
    SparkApplication running = application("kyuubi-session", "analyst", false);
    when(history.applications(500, null)).thenReturn(List.of());
    when(kyuubi.runningApplications()).thenReturn(List.of(running));

    assertThat(access.applications(user("analyst"), 500, null, null)).containsExactly(running);
  }

  @Test
  void flinkTabKeepsFlinkJobsOutOfTheSparkList() throws Exception {
    SparkApplication engine = application("kyuubi-analyst", "analyst", false);
    when(history.applications(500, null)).thenReturn(List.of());
    when(kyuubi.runningApplicationsForAllUsers()).thenReturn(List.of(engine));
    when(flink.runningApplications())
        .thenReturn(List.of(new SparkApplication("flink-abc", "wordcount", "", "", "", 0, false, "Flink job")));

    // The Spark applications list never contains Flink jobs; they have their own tab.
    assertThat(access.applications(administrator(), 500, null, null)).containsExactly(engine);
  }

  @Test
  void administratorGetsKyuubiAndStandaloneFlinkJobs() throws Exception {
    SparkApplication engine = application("kyuubi-flink-analyst", "analyst", false);
    SqlQueryInfo query = new SqlQueryInfo("query-1", "SELECT 1", "FINISHED", 1_000, 2_000, "", 1, List.of());
    SparkApplication running = new SparkApplication("flink-run", "stream", "", "", "", 0, false, "Flink job");
    SparkApplication done = new SparkApplication("flink-done", "batch", "", "", "", 0, true, "Flink job");
    when(kyuubiFlink.runningApplicationsForAllUsers()).thenReturn(List.of(engine));
    when(sqlHistory.entriesForAllUsers("kyuubi-flink"))
        .thenReturn(List.of(new SqlQueryHistory.OwnedEntry("analyst", query)));
    when(flink.runningApplications()).thenReturn(List.of(running));
    when(flink.completedApplications()).thenReturn(List.of(done));

    assertThat(access.flinkApplications(administrator()))
        .extracting(SparkApplication::id)
        .containsExactly("kyuubi-flink-analyst", "kyuubi-flink-query-query-1", "flink-run", "flink-done");
  }

  @Test
  void userGetsOwnKyuubiFlinkEngineButNotStandaloneFlinkJobs() throws Exception {
    SparkApplication engine = application("kyuubi-flink-analyst", "analyst", false);
    SqlQueryInfo query = new SqlQueryInfo("query-1", "SELECT 1", "FINISHED", 1_000, 2_000, "", 1, List.of());
    when(kyuubiFlink.runningApplications()).thenReturn(List.of(engine));
    when(sqlHistory.entries("kyuubi-flink")).thenReturn(List.of(query));

    assertThat(access.flinkApplications(user("analyst")))
        .extracting(SparkApplication::id)
        .containsExactly("kyuubi-flink-analyst", "kyuubi-flink-query-query-1");
  }

  @Test
  void ownerCanOpenLiveFlinkOverviewWhileKyuubiEngineIsRunning() {
    when(kyuubiFlink.runningApplications())
        .thenReturn(List.of(application("kyuubi-flink-analyst", "analyst", false)));

    assertThat(access.canUseFlinkUi(user("analyst"))).isTrue();
  }

  @Test
  void kyuubiFlinkQueryUsesRecordedJobIdAndGrantsItsOwnerAccess() {
    String jobId = "0123456789abcdef0123456789abcdef";
    SqlQueryInfo query =
        new SqlQueryInfo(
            "query-1",
            "SELECT 1",
            "FINISHED",
            1_000,
            2_000,
            "",
            1,
            List.of("Submitting job 'SELECT 1' (" + jobId + ")."));
    when(sqlHistory.entries("kyuubi-flink")).thenReturn(List.of(query));

    assertThat(access.flinkApplications(user("analyst")))
        .extracting(SparkApplication::id)
        .containsExactly("flink-" + jobId);
    assertThat(access.canOpenFlinkJob(user("analyst"), jobId)).isTrue();
    assertThat(access.canOpenFlinkJob(user("analyst"), "fedcba9876543210fedcba9876543210")).isFalse();
    assertThat(access.canUseFlinkUi(user("analyst"))).isTrue();
  }

  private static SparkApplication application(String id, String user, boolean completed) {
    return new SparkApplication(id, id, user, "2026-07-29T00:00:00", "", 0, completed, "4.0");
  }

  private static UsernamePasswordAuthenticationToken user(String username) {
    return UsernamePasswordAuthenticationToken.authenticated(
        username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }

  private static UsernamePasswordAuthenticationToken administrator() {
    return UsernamePasswordAuthenticationToken.authenticated(
        "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")));
  }
}
