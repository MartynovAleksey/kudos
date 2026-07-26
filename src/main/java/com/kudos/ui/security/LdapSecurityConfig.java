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

import jakarta.servlet.DispatcherType;
import javax.security.auth.login.LoginException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.ldap.LdapBindAuthenticationManagerFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class LdapSecurityConfig {

  /**
   * Authenticates against LDAP and then, with the same password, obtains the
   * user's Kerberos ticket. Both have to succeed: without a ticket the session
   * could not reach a single one of the cluster services, so an authenticated
   * user with no credentials to use would be a worse outcome than a refusal.
   */
  @Bean
  AuthenticationManager ldapAuthenticationManager(
      BaseLdapPathContextSource source,
      @Value("${spring.ldap.user-dn-pattern}") String userDnPattern,
      KerberosTicketService tickets,
      LoginAttemptService attempts) {
    var factory = new LdapBindAuthenticationManagerFactory(source);
    factory.setUserDnPatterns(userDnPattern);
    AuthenticationManager ldap = factory.createAuthenticationManager();

    return authentication -> {
      String username = authentication.getName();
      // A locked account is refused before any credential is checked, so the
      // password cannot keep being tried while the cooldown runs.
      if (attempts.isBlocked(username)) {
        throw new LockedException("Too many failed attempts; the account is temporarily locked");
      }
      try {
        Authentication bound = ldap.authenticate(authentication);
        Object presented = authentication.getCredentials();
        if (presented == null) {
          throw new BadCredentialsException("No password to obtain a Kerberos ticket with");
        }
        KerberosTicketService.IssuedTicket ticket =
            tickets.login(bound.getName(), presented.toString());
        attempts.loginSucceeded(username);
        return new KerberosAuthentication(
            bound.getName(), ticket.subject(), ticket.expiresAt(), bound.getAuthorities());
      } catch (LoginException failure) {
        attempts.loginFailed(username);
        throw new BadCredentialsException(
            "LDAP accepted the password but the KDC refused it", failure);
      } catch (AuthenticationException failure) {
        attempts.loginFailed(username);
        throw failure;
      }
    };
  }

  /**
   * The scripted clients documented in the README call {@code /api} with HTTP
   * Basic and expect a 401 challenge, not a redirect to a login page. Giving
   * {@code /api} its own chain keeps that contract exact: a single chain has to
   * choose one entry point from the request, and the browser's Accept header
   * made it pick the wrong one for token-free API calls.
   *
   * <p>The chain reuses the session created at form login rather than being
   * stateless. The UI fetches {@code /api} from the browser with its session
   * cookie; a stateless chain ignored that cookie, answered 401 Basic, and the
   * browser popped a second, native login box. Honouring the existing session
   * lets a signed-in browser through with no challenge, while a script that
   * arrives with neither session nor credentials still gets the 401 (it never
   * creates a session of its own — {@code NEVER}, not {@code IF_REQUIRED}).
   */
  @Bean
  @Order(1)
  SecurityFilterChain apiSecurity(
      HttpSecurity http, AuthenticationManager ldapAuthenticationManager) throws Exception {
    return http
        .securityMatcher("/api/**")
        // Token-free so curl and scripts stay simple: a call carries Basic, or a
        // browser carries the session cookie from its form login.
        .csrf(csrf -> csrf.disable())
        .authenticationManager(ldapAuthenticationManager)
        .authorizeHttpRequests(authorization -> authorization.anyRequest().authenticated())
        .httpBasic(basic -> basic.authenticationEntryPoint(basicAuthenticationEntryPoint()))
        // Pin the challenge to Basic explicitly. Without this a missing
        // credential is handled by the default entry point, which saves the
        // request and redirects to the login page instead of answering 401.
        .exceptionHandling(
            handling -> handling.authenticationEntryPoint(basicAuthenticationEntryPoint()))
        .requestCache(cache -> cache.disable())
        // Use the form-login session if the browser already has one, but never
        // start one for a script: an anonymous curl still gets a clean 401.
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.NEVER))
        // A browser call carrying a session whose ticket has expired is refused
        // and the session dropped, rather than served from a ticket the cluster
        // would no longer honour.
        .addFilterAfter(new TicketExpiryFilter(), SecurityContextHolderFilter.class)
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain uiSecurity(
      HttpSecurity http,
      AuthenticationManager ldapAuthenticationManager,
      LoginAttemptService attempts)
      throws Exception {
    return http
        .authenticationManager(ldapAuthenticationManager)
        .authorizeHttpRequests(
            authorization ->
                authorization
                    // A 401 from the API chain becomes a container ERROR
                    // dispatch to /error; without this it would be re-secured
                    // here and answered with a login redirect instead.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/login",
                        "/static/**",
                        "/error",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            login ->
                login
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    // "/" resolves to the first enabled screen, so login works
                    // even when the editor module is disabled.
                    .defaultSuccessUrl("/", true)
                    // Tell the login page how many tries remain, or how long the
                    // account is locked, so the user is not left guessing.
                    .failureHandler(
                        (request, response, exception) -> {
                          String username = request.getParameter("username");
                          if (attempts.isBlocked(username)) {
                            response.sendRedirect(
                                request.getContextPath()
                                    + "/login?locked&seconds="
                                    + attempts.lockSecondsRemaining(username));
                          } else {
                            response.sendRedirect(
                                request.getContextPath()
                                    + "/login?error&remaining="
                                    + attempts.remaining(username));
                          }
                        })
                    .permitAll())
        // The job detail screen embeds the proxied Spark UI in an iframe from
        // this same origin, which the default DENY would block.
        .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
        .logout(
            logout ->
                logout
                    // The sidebar signs out with a plain link and the countdown
                    // signs out by navigating, so accept GET, not only POST.
                    .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                    // Wipe the ticket's key material as the session goes.
                    .addLogoutHandler(new KerberosTicketCleanup())
                    .addLogoutHandler(new SecurityContextLogoutHandler())
                    .logoutSuccessUrl("/login?logout")
                    .permitAll())
        // The browser uses the session, but keeping Basic here lets a script
        // reach the proxied Spark UI the same way it reaches /api. An
        // unauthenticated page request still lands on the login form, since
        // that is the entry point below.
        .httpBasic(Customizer.withDefaults())
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
        // Expired tickets end the session here too, so no screen renders for a
        // user the cluster would already turn away.
        .addFilterAfter(new TicketExpiryFilter(), SecurityContextHolderFilter.class)
        .build();
  }

  private static BasicAuthenticationEntryPoint basicAuthenticationEntryPoint() {
    var entryPoint = new BasicAuthenticationEntryPoint();
    entryPoint.setRealmName("kudos");
    return entryPoint;
  }
}
