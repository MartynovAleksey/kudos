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
package com.kudos.ui.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.kerberos.KerberosTicket;
import org.junit.jupiter.api.Test;

class KerberosExecutorTests {

  @Test
  void createsClusterSubjectWithOnlyTheTicketGrantingTicket() {
    KerberosPrincipal user = new KerberosPrincipal("alice@EXAMPLE.COM");
    KerberosTicket ticketGrantingTicket = ticket(user, "krbtgt/EXAMPLE.COM@EXAMPLE.COM");
    KerberosTicket serviceTicket = ticket(user, "HTTP/trino.example.com@EXAMPLE.COM");
    Subject sessionSubject = new Subject();
    sessionSubject.getPrincipals().add(user);
    sessionSubject.getPrivateCredentials().add(ticketGrantingTicket);
    sessionSubject.getPrivateCredentials().add(serviceTicket);

    Subject clusterSubject = KerberosExecutor.subjectForClusterCall(sessionSubject);

    assertThat(clusterSubject).isNotSameAs(sessionSubject);
    assertThat(clusterSubject.getPrincipals()).containsExactly(user);
    assertThat(clusterSubject.getPrivateCredentials(KerberosTicket.class))
        .containsExactly(ticketGrantingTicket);
    assertThat(sessionSubject.getPrivateCredentials(KerberosTicket.class))
        .containsExactlyInAnyOrder(ticketGrantingTicket, serviceTicket);
  }

  private static KerberosTicket ticket(KerberosPrincipal client, String server) {
    Date start = new Date();
    Date end = new Date(start.getTime() + 60_000);
    return new KerberosTicket(
        new byte[] {1},
        client,
        new KerberosPrincipal(server),
        new byte[] {1},
        1,
        new boolean[7],
        start,
        start,
        end,
        end,
        null);
  }
}
