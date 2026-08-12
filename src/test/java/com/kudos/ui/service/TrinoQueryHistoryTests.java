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

package com.kudos.ui.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TrinoQueryHistoryTests {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void keepsQueriesAndWarningsForTheAuthenticatedUser() {
    TrinoQueryHistory history = new TrinoQueryHistory();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "n/a"));

    String id = history.start("SELECT 1");
    history.finish(id, List.of("Trino warning"));

    TrinoQueryInfo entry = history.entries().getFirst();
    assertThat(entry.id()).isEqualTo(id);
    assertThat(entry.statement()).isEqualTo("SELECT 1");
    assertThat(entry.state()).isEqualTo("FINISHED");
    assertThat(entry.logs()).containsExactly("Trino warning");
    assertThat(history.sql(id)).isEqualTo("SELECT 1");

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("bob", "n/a"));
    assertThat(history.entries()).isEmpty();
  }
}
