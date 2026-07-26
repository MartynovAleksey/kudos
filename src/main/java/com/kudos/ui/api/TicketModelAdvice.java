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

package com.kudos.ui.api;

import com.kudos.ui.security.KerberosAuthentication;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the signed-in user's remaining ticket time on every rendered screen so
 * the layout can show a countdown. Only the page controller is advised; the
 * JSON API and the login page (anonymous) neither need nor get these values.
 */
@ControllerAdvice(assignableTypes = UiController.class)
public class TicketModelAdvice {

  @ModelAttribute
  void addTicketExpiry(Model model) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof KerberosAuthentication kerberos
        && kerberos.expiresAt() != null) {
      long remaining = Duration.between(Instant.now(), kerberos.expiresAt()).getSeconds();
      // Seed the browser clock with the server-computed remainder so the
      // countdown is immune to a clock offset between the two.
      model.addAttribute("ticketRemainingSeconds", Math.max(remaining, 0));
    }
  }
}
