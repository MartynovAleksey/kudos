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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class LdapRoleMappingTests {

  @Test
  void administratorGroupMapsToAdministratorRole() {
    var ldapAuthentication =
        UsernamePasswordAuthenticationToken.authenticated(
            "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_KUDOS-ADMINISTRATORS")));

    assertThat(LdapSecurityConfig.uiAuthorities(ldapAuthentication))
        .extracting(SimpleGrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMINISTRATOR");
  }

  @Test
  void userOutsideAdministratorGroupMapsToUserRole() {
    var ldapAuthentication =
        UsernamePasswordAuthenticationToken.authenticated("analyst", "n/a", List.of());

    assertThat(LdapSecurityConfig.uiAuthorities(ldapAuthentication))
        .extracting(SimpleGrantedAuthority::getAuthority)
        .containsExactly("ROLE_USER");
  }
}
