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

package com.kudos.ui.service;

import com.kudos.ui.security.RoleAccess;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Server-side owner filter shared by Jobs endpoints and the Spark UI proxy. */
@Service
public class SparkApplicationAccessService {

  private static final Pattern FLINK_JOB_ID =
      Pattern.compile("Submitting job '.*?' \\(([0-9a-f]{32})\\)");

  private final SparkHistoryService history;
  private final KyuubiService kyuubi;
  private final KyuubiFlinkService kyuubiFlink;
  private final SqlQueryHistory sqlHistory;
  private final FlinkService flink;
  private final RoleAccess roles;

  public SparkApplicationAccessService(
      SparkHistoryService history,
      KyuubiService kyuubi,
      KyuubiFlinkService kyuubiFlink,
      SqlQueryHistory sqlHistory,
      FlinkService flink,
      RoleAccess roles) {
    this.history = history;
    this.kyuubi = kyuubi;
    this.kyuubiFlink = kyuubiFlink;
    this.sqlHistory = sqlHistory;
    this.flink = flink;
    this.roles = roles;
  }

  public List<SparkApplication> applications(
      Authentication authentication, int limit, String minDate, String requestedUser) throws Exception {
    boolean administrator = roles.isAdministrator(authentication);
    String user = roles.username(authentication);
    String filter = administrator ? requestedUser : user;
    return Stream.concat(
            (administrator ? kyuubi.runningApplicationsForAllUsers() : kyuubi.runningApplications())
                .stream(),
            history.applications(Math.clamp(limit, 1, 5_000), minDate).stream())
        .filter(application -> filter == null || filter.isBlank() || filter.equals(application.user()))
        .toList();
  }

  /**
   * Kyuubi Flink engines plus standalone Flink jobs for the separate Flink tab.
   * Kyuubi engines retain their KUDOS owner and are visible to that user; standalone
   * Flink jobs do not report an owner and remain administrator-only.
   */
  public List<SparkApplication> flinkApplications(Authentication authentication) {
    boolean administrator = roles.isAdministrator(authentication);
    List<SparkApplication> kyuubiEngines =
        administrator
            ? kyuubiFlink.runningApplicationsForAllUsers()
            : kyuubiFlink.runningApplications();
    List<SparkApplication> kyuubiQueries =
        administrator
            ? sqlHistory.entriesForAllUsers("kyuubi-flink").stream()
                .map(entry -> kyuubiQuery(entry.user(), entry.query()))
                .toList()
            : sqlHistory.entries("kyuubi-flink").stream()
                .map(query -> kyuubiQuery(roles.username(authentication), query))
                .toList();
    if (!administrator) {
      return Stream.concat(kyuubiEngines.stream(), kyuubiQueries.stream()).toList();
    }
    LinkedHashMap<String, SparkApplication> applications = new LinkedHashMap<>();
    Stream.concat(
            Stream.concat(kyuubiEngines.stream(), kyuubiQueries.stream()),
            Stream.concat(flink.runningApplications().stream(), flink.completedApplications().stream()))
        .forEach(application -> applications.putIfAbsent(application.id(), application));
    return List.copyOf(applications.values());
  }

  private static SparkApplication kyuubiQuery(String user, SqlQueryInfo query) {
    boolean completed = !"RUNNING".equals(query.state());
    long finishedAt = completed ? query.completedAtEpochMs() : 0;
    return new SparkApplication(
        flinkJobId(query).map(jobId -> "flink-" + jobId).orElse("kyuubi-flink-query-" + query.id()),
        query.statement(),
        user,
        java.time.Instant.ofEpochMilli(query.startedAtEpochMs()).toString(),
        finishedAt == 0 ? "" : java.time.Instant.ofEpochMilli(finishedAt).toString(),
        finishedAt == 0 ? 0 : Math.max(0, finishedAt - query.startedAtEpochMs()),
        completed,
        "Kyuubi Flink SQL query");
  }

  /** Returns the real Flink job identifier emitted by the Kyuubi FLINK_SQL engine. */
  private static Optional<String> flinkJobId(SqlQueryInfo query) {
    return query.logs().stream()
        .map(FLINK_JOB_ID::matcher)
        .filter(java.util.regex.Matcher::find)
        .map(matcher -> matcher.group(1))
        .findFirst();
  }

  public boolean canView(Authentication authentication, String applicationId) throws Exception {
    if (roles.isAdministrator(authentication)) {
      return true;
    }
    String user = roles.username(authentication);
    return history
        .application(applicationId)
        .map(application -> user.equals(application.user()))
        .orElse(false);
  }

  /** Grants a user access only when the Flink job-id was recorded for their Kyuubi query. */
  public boolean canOpenFlinkJob(Authentication authentication, String jobId) {
    if (roles.isAdministrator(authentication)) {
      return true;
    }
    return sqlHistory.entries("kyuubi-flink").stream()
        .flatMap(query -> flinkJobId(query).stream())
        .anyMatch(jobId::equals);
  }

  /**
   * Flink assets omit the job-id. An owner may therefore open the live JobManager overview while
   * their Kyuubi FLINK_SQL engine exists, or the History UI after a query recorded its real id.
   */
  public boolean canUseFlinkUi(Authentication authentication) {
    return roles.isAdministrator(authentication)
        || !kyuubiFlink.runningApplications().isEmpty()
        || sqlHistory.entries("kyuubi-flink").stream().anyMatch(query -> flinkJobId(query).isPresent());
  }

  public boolean isAdministrator(Authentication authentication) {
    return roles.isAdministrator(authentication);
  }
}
