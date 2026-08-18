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

import com.kudos.ui.config.UiProperties;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineVisibilityStoreTests {

  @Test
  void everyEngineIsVisibleWhenNothingIsConfigured() {
    EngineVisibilityStore store = new EngineVisibilityStore(new UiProperties(null, null, null));

    assertThat(store.visibility()).containsAllEntriesOf(
        Map.of("kyuubi", true, "kyuubi-flink", true, "trino", true, "starrocks", true));
  }

  @Test
  void updateHidesOnlyTheNamedEngines() {
    EngineVisibilityStore store = new EngineVisibilityStore(new UiProperties(null, null, null));

    store.update(Map.of("trino", false));

    assertThat(store.visibility().get("trino")).isFalse();
    assertThat(store.visibility().get("kyuubi")).isTrue();
  }

  @Test
  void theChoiceIsPersistedAndReloadedFromFile(@TempDir Path dir) {
    Path file = dir.resolve("engine-visibility.json");
    UiProperties props = new UiProperties(null, null, file.toString());

    new EngineVisibilityStore(props).update(Map.of("starrocks", false));

    assertThat(new EngineVisibilityStore(props).visibility().get("starrocks")).isFalse();
  }
}
