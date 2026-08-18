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

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SqlQueryHistoryTests {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void keepsLogsAndCompactsConsecutiveQueriesPerUserAndEngine() {
    SqlQueryHistory history = new SqlQueryHistory();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "n/a"));

    String first = history.start("trino", "SELECT 1");
    history.finish("trino", first, List.of("Trino warning"));
    String second = history.start("trino", " SELECT   1 ");
    history.finish("trino", second, List.of());

    SqlQueryInfo entry = history.entries("trino").getFirst();
    assertThat(entry.id()).isEqualTo(second);
    assertThat(entry.executionCount()).isEqualTo(2);
    assertThat(entry.logs()).isEmpty();
    assertThat(history.sql("trino", second)).isEqualTo(" SELECT   1 ");
    assertThat(history.entries("starrocks")).isEmpty();

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("bob", "n/a"));
    assertThat(history.entries("trino")).isEmpty();
  }
}
