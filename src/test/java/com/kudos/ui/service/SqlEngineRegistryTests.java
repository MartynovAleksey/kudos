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
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlEngineRegistryTests {

  @Test
  void resolvesKnownEnginesAndFallsBackToKyuubi() {
    SqlEngine kyuubi = new StubEngine("kyuubi", "Kyuubi Spark SQL", true);
    SqlEngine trino = new StubEngine("trino", "Trino", false);
    SqlEngine starrocks = new StubEngine("starrocks", "StarRocks", false);
    SqlEngineRegistry registry = new SqlEngineRegistry(List.of(kyuubi, trino, starrocks));

    assertThat(registry.get("trino")).isSameAs(trino);
    assertThat(registry.get("starrocks")).isSameAs(starrocks);
    assertThat(registry.get(null)).isSameAs(kyuubi);
    assertThat(registry.get("unknown")).isSameAs(kyuubi);
    assertThat(registry.available())
        .containsExactly(
            new SqlEngineInfo("kyuubi", "Kyuubi Spark SQL", true),
            new SqlEngineInfo("trino", "Trino", false),
            new SqlEngineInfo("starrocks", "StarRocks", false));
  }

  private record StubEngine(String id, String displayName, boolean supportsSessions) implements SqlEngine {
    @Override
    public QueryResult execute(String sql, int maxRows) {
      return QueryResult.ok("OK");
    }

    @Override
    public List<Map<String, Object>> query(String sql) {
      return List.of();
    }
  }
}
