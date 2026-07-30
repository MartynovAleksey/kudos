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
import java.util.List;
import java.util.stream.Stream;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Server-side owner filter shared by Jobs endpoints and the Spark UI proxy. */
@Service
public class SparkApplicationAccessService {

  private final SparkHistoryService history;
  private final KyuubiService kyuubi;
  private final RoleAccess roles;

  public SparkApplicationAccessService(
      SparkHistoryService history, KyuubiService kyuubi, RoleAccess roles) {
    this.history = history;
    this.kyuubi = kyuubi;
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

  public boolean isAdministrator(Authentication authentication) {
    return roles.isAdministrator(authentication);
  }
}
