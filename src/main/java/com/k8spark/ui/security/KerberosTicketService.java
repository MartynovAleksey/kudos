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

package com.k8spark.ui.security;

import com.k8spark.ui.config.ClusterProperties;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.springframework.stereotype.Service;

/**
 * Obtains a Kerberos ticket-granting ticket for a user from their password, the
 * same exchange {@code kinit} performs.
 *
 * <p>The application holds no keytabs: a request is made with the ticket of the
 * user who signed in, so HDFS, Ozone, HBase and Kyuubi see that user and apply
 * their own authorization to them.
 */
@Service
public class KerberosTicketService {

  private static final String LOGIN_MODULE = "com.sun.security.auth.module.Krb5LoginModule";

  private final ClusterProperties properties;

  public KerberosTicketService(ClusterProperties properties) {
    this.properties = properties;
  }

  /**
   * A user's Kerberos ticket together with the instant it stops being valid.
   * The session that holds it must not outlive that instant: past it the ticket
   * would be refused by every service, so the session is worthless.
   */
  public record IssuedTicket(Subject subject, Instant expiresAt) {}

  /**
   * @return the subject holding the user's TGT and the ticket's expiry
   * @throws LoginException if the KDC rejects the credentials
   */
  public IssuedTicket login(String username, String password) throws LoginException {
    String principal = properties.kerberosPrincipal().replace("{user}", username);
    Subject subject = new Subject();
    LoginContext context =
        new LoginContext(
            "k8spark-ui",
            subject,
            callbacks -> {
              for (Callback callback : callbacks) {
                if (callback instanceof NameCallback name) {
                  name.setName(principal);
                } else if (callback instanceof PasswordCallback secret) {
                  secret.setPassword(password.toCharArray());
                }
              }
            },
            configurationFor(principal));
    context.login();
    Subject authenticated = context.getSubject();
    return new IssuedTicket(authenticated, earliestExpiry(authenticated));
  }

  /**
   * The soonest a credential in the subject expires — in practice the TGT's end
   * time. Bounding the session by the earliest is the safe choice: once any
   * ticket has lapsed the subject can no longer do the work it was kept for.
   */
  private static Instant earliestExpiry(Subject subject) {
    Instant earliest = null;
    for (KerberosTicket ticket : subject.getPrivateCredentials(KerberosTicket.class)) {
      Date end = ticket.getEndTime();
      if (end == null) {
        continue;
      }
      Instant candidate = end.toInstant();
      if (earliest == null || candidate.isBefore(earliest)) {
        earliest = candidate;
      }
    }
    return earliest;
  }

  private static Configuration configurationFor(String principal) {
    Map<String, String> options = new HashMap<>();
    options.put("principal", principal);
    // The ticket lives in the session, never on disk, and no local cache is
    // consulted: the password presented at login is the only way in.
    options.put("useTicketCache", "false");
    options.put("useKeyTab", "false");
    options.put("storeKey", "false");
    options.put("doNotPrompt", "false");
    options.put("refreshKrb5Config", "true");
    AppConfigurationEntry entry =
        new AppConfigurationEntry(LOGIN_MODULE, LoginModuleControlFlag.REQUIRED, options);
    return new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        return new AppConfigurationEntry[] {entry};
      }
    };
  }
}
