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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.ldap.LdapBindAuthenticationManagerFactory;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class LdapSecurityConfig {

  @Bean
  AuthenticationManager ldapAuthenticationManager(
      BaseLdapPathContextSource source,
      @Value("${spring.ldap.user-dn-pattern}") String userDnPattern) {
    var factory = new LdapBindAuthenticationManagerFactory(source);
    factory.setUserDnPatterns(userDnPattern);
    return factory.createAuthenticationManager();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, AuthenticationManager ldapAuthenticationManager) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .authenticationManager(ldapAuthenticationManager)
        .authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }
}
