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
package com.kudos.ui.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Component;

/** Centralizes the two UI roles without taking over data authorization in Hadoop services. */
@Component
public class RoleAccess {

  public boolean isAdministrator(Authentication authentication) {
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMINISTRATOR".equals(authority.getAuthority()));
  }

  public boolean isSecurityOfficer(Authentication authentication) {
    return authentication != null && authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_SECURITY_OFFICER".equals(authority.getAuthority()));
  }

  /** Security officers without the administrator role cannot use tool APIs. */
  public boolean canUseToolApis(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && (!isSecurityOfficer(authentication) || isAdministrator(authentication));
  }

  /** Security officers without the administrator role cannot open tool pages. */
  public boolean canUseToolPages(Authentication authentication) {
    return canUseToolApis(authentication);
  }

  public String username(Authentication authentication) {
    if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
      throw new IllegalStateException("Authenticated user is required");
    }
    return authentication.getName();
  }
}
