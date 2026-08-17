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

import com.kudos.ui.config.UiProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolVisibilityStoreTests {

  @Test
  void everyToolIsVisibleWhenNothingIsConfigured() {
    ToolVisibilityStore store = new ToolVisibilityStore(new UiProperties(null, null, null));

    assertThat(store.visibility()).containsAllEntriesOf(
        Map.of("editor", true, "files", true, "ozone", true, "hbase", true, "jobs", true));
  }

  @Test
  void updateHidesOnlyTheNamedToolsAndKeepsTheRestVisible() {
    ToolVisibilityStore store = new ToolVisibilityStore(new UiProperties(null, null, null));

    store.update(Map.of("hbase", false));

    assertThat(store.visibility().get("hbase")).isFalse();
    assertThat(store.visibility().get("editor")).isTrue();
    assertThat(store.visibility().get("jobs")).isTrue();
  }

  @Test
  void unknownKeysCannotBlankOutTheKnownTools() {
    ToolVisibilityStore store = new ToolVisibilityStore(new UiProperties(null, null, null));

    store.update(Map.of("nonsense", false));

    assertThat(store.visibility().values()).allMatch(Boolean::booleanValue);
  }

  @Test
  void theChoiceIsPersistedAndReloadedFromFile(@TempDir Path dir) {
    Path file = dir.resolve("tool-visibility.json");
    UiProperties props = new UiProperties(null, file.toString(), null);

    new ToolVisibilityStore(props).update(Map.of("files", false));

    // A fresh store (as after a restart) reads the persisted choice back.
    assertThat(new ToolVisibilityStore(props).visibility().get("files")).isFalse();
  }

  @Test
  void aCorruptFileFallsBackToShowingEverything(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("tool-visibility.json");
    Files.writeString(file, "{ not json");

    ToolVisibilityStore store = new ToolVisibilityStore(new UiProperties(null, file.toString(), null));

    assertThat(store.visibility().values()).allMatch(Boolean::booleanValue);
  }
}
