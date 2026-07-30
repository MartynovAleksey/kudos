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

import com.kudos.ui.security.KerberosAuthentication;
import java.security.PrivilegedExceptionAction;
import javax.security.auth.Subject;
import jakarta.annotation.PostConstruct;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Runs a cluster call as the signed-in user, using the Kerberos ticket obtained
 * for them at login.
 *
 * <p>The application holds no credentials of its own, so it cannot reach a
 * service except on behalf of a user who has authenticated. Each service then
 * applies its own authorization to the identity in that ticket, which is why
 * there is no authorization layer here to keep in step with them.
 */
@Service
public class KerberosExecutor {

  /**
   * UGI keeps its security configuration in static process-wide state. Set it
   * once at startup: changing it for every request races concurrent HDFS,
   * HBase and Ozone calls and can downgrade an Ozone client to simple auth.
   */
  @PostConstruct
  void enableKerberosForHadoopClients() {
    Configuration configuration = new Configuration(false);
    configuration.set("hadoop.security.authentication", "kerberos");
    UserGroupInformation.setConfiguration(configuration);
  }

  public <T> T asLoggedInUser(PrivilegedExceptionAction<T> action) throws Exception {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof KerberosAuthentication kerberos)) {
      throw new IllegalStateException(
          "This session holds no Kerberos ticket; sign in again to obtain one");
    }
    Subject subject = kerberos.subject();
    if (subject == null) {
      // Reachable only if the session outlived the process that created it: the
      // ticket is deliberately not carried across a restart.
      throw new IllegalStateException(
          "The Kerberos ticket for this session is gone; sign in again");
    }
    return UserGroupInformation.getUGIFromSubject(subject).doAs(action);
  }
}
