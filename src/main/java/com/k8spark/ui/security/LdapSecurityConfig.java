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
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

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
        // The UI posts forms with a token; /api stays token-free so the scripted
        // Basic-auth clients documented in the README keep working unchanged.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
        .authenticationManager(ldapAuthenticationManager)
        .authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers("/actuator/health", "/login", "/static/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            login ->
                login
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/editor", true)
                    .failureUrl("/login?error")
                    .permitAll())
        // The job detail screen embeds the proxied Spark UI in an iframe from
        // this same origin, which the default DENY would block.
        .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
        .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
        // Retained so the same endpoints stay usable from curl and scripts.
        .httpBasic(Customizer.withDefaults())
        // Pin both challenges by path. Left to its defaults Spring Security picks
        // between them from the Accept header, so a page request without an
        // explicit text/html preference would get a Basic challenge instead of
        // the login form.
        .exceptionHandling(
            handling ->
                handling
                    .defaultAuthenticationEntryPointFor(
                        basicAuthenticationEntryPoint(),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                    .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
        .build();
  }

  private static BasicAuthenticationEntryPoint basicAuthenticationEntryPoint() {
    var entryPoint = new BasicAuthenticationEntryPoint();
    entryPoint.setRealmName("k8spark-ui");
    return entryPoint;
  }
}
