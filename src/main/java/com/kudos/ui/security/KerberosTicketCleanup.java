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

package com.kudos.ui.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Destroys the user's Kerberos tickets when their session ends, whether they
 * signed out or the ticket lapsed. Invalidating the session drops the reference
 * to the {@link Subject}, but wiping the ticket's key material at once means it
 * cannot be recovered from a heap that has not yet been collected.
 */
public class KerberosTicketCleanup implements LogoutHandler {

  @Override
  public void logout(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    if (!(authentication instanceof KerberosAuthentication kerberos)) {
      return;
    }
    Subject subject = kerberos.subject();
    if (subject == null) {
      return;
    }
    for (KerberosTicket ticket : subject.getPrivateCredentials(KerberosTicket.class)) {
      try {
        ticket.destroy();
      } catch (DestroyFailedException ignored) {
        // Best effort: some providers refuse destroy(); the session is gone
        // regardless, so there is nothing more to do here.
      }
    }
  }
}
