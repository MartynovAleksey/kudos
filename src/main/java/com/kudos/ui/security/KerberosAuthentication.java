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

import java.io.Serial;
import java.time.Instant;
import java.util.Collection;
import javax.security.auth.Subject;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * An authenticated user together with the Kerberos ticket obtained for them at
 * login. The ticket lives only in this token, and therefore only for the life
 * of the session that holds it, and no longer than {@link #expiresAt()}.
 */
public class KerberosAuthentication extends AbstractAuthenticationToken {

  @Serial private static final long serialVersionUID = 1L;

  private final String username;
  private final transient Subject subject;
  private final Instant expiresAt;

  public KerberosAuthentication(
      String username,
      Subject subject,
      Instant expiresAt,
      Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.username = username;
    this.subject = subject;
    this.expiresAt = expiresAt;
    setAuthenticated(true);
  }

  public Subject subject() {
    return subject;
  }

  /** When the held ticket stops being valid, or {@code null} if unknown. */
  public Instant expiresAt() {
    return expiresAt;
  }

  @Override
  public Object getPrincipal() {
    return username;
  }

  /** The password is never retained; the ticket obtained with it is. */
  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return username;
  }
}
