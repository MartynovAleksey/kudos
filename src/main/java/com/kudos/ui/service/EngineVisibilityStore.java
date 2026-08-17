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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kudos.ui.config.UiProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds which SQL engines are shown in the editor, as an administrator chooses.
 * It mirrors {@link ToolVisibilityStore}: one deployment-wide setting, persisted
 * to a small JSON file when configured, applied to every user on their next page
 * load. Visibility only hides the engine's editor tab; server-side authorization
 * is unchanged. The default shows every engine.
 *
 * <p>ponytail: a near-copy of {@link ToolVisibilityStore}; kept separate rather
 * than generalised to avoid reworking that class's bean wiring and tests.
 */
@Component
public class EngineVisibilityStore {

  /** The toggleable engines, keyed by their registry id. */
  public static final List<String> ENGINES = List.of("kyuubi", "kyuubi-flink", "trino", "starrocks");

  private static final Logger log = LoggerFactory.getLogger(EngineVisibilityStore.class);

  private final Path file;
  private final ObjectMapper json = new ObjectMapper();
  private volatile Map<String, Boolean> visibility;

  public EngineVisibilityStore(UiProperties ui) {
    String path = ui.engineVisibilityFile();
    this.file = (path == null || path.isBlank()) ? null : Path.of(path);
    this.visibility = load();
  }

  private static Map<String, Boolean> defaults() {
    Map<String, Boolean> all = new LinkedHashMap<>();
    ENGINES.forEach(engine -> all.put(engine, true));
    return all;
  }

  public synchronized Map<String, Boolean> visibility() {
    return new LinkedHashMap<>(visibility);
  }

  public synchronized void update(Map<String, Boolean> incoming) {
    Map<String, Boolean> next = defaults();
    if (incoming != null) {
      for (String engine : ENGINES) {
        Object value = incoming.get(engine);
        if (value instanceof Boolean chosen) {
          next.put(engine, chosen);
        }
      }
    }
    visibility = next;
    save();
  }

  private Map<String, Boolean> load() {
    if (file == null || !Files.isReadable(file)) {
      return defaults();
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = json.readValue(Files.readAllBytes(file), Map.class);
      Map<String, Boolean> loaded = defaults();
      for (String engine : ENGINES) {
        if (parsed.get(engine) instanceof Boolean chosen) {
          loaded.put(engine, chosen);
        }
      }
      return loaded;
    } catch (IOException | RuntimeException failure) {
      log.warn("Could not read engine visibility from {}: {}", file, failure.toString());
      return defaults();
    }
  }

  private void save() {
    if (file == null) {
      return;
    }
    try {
      Files.write(file, json.writeValueAsBytes(visibility));
    } catch (IOException failure) {
      log.warn("Could not persist engine visibility to {}: {}", file, failure.toString());
    }
  }
}
