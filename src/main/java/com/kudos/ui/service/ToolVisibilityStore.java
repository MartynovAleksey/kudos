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
 * Holds which left-panel tools are visible, as an administrator chooses. This is
 * one shared setting for the whole deployment, not a per-user preference, so a
 * hidden tool disappears for every user on their next page load. The choice is
 * persisted to a small JSON file when {@code kudos.ui.tool-visibility-file} is
 * set, and kept in memory only otherwise.
 *
 * <p>Visibility is cosmetic: it never widens or narrows real access, which the
 * server's role checks still decide. The default shows every tool.
 */
@Component
public class ToolVisibilityStore {

  /** The configurable tools, in their fixed left-panel order. */
  public static final List<String> TOOLS = List.of("editor", "files", "ozone", "hbase", "jobs");

  private static final Logger log = LoggerFactory.getLogger(ToolVisibilityStore.class);

  private final Path file;
  private final ObjectMapper json = new ObjectMapper();
  private volatile Map<String, Boolean> visibility;

  public ToolVisibilityStore(UiProperties ui) {
    String path = ui.toolVisibilityFile();
    this.file = (path == null || path.isBlank()) ? null : Path.of(path);
    this.visibility = load();
  }

  /** All tools visible — the state when nothing has been configured yet. */
  private static Map<String, Boolean> defaults() {
    Map<String, Boolean> all = new LinkedHashMap<>();
    TOOLS.forEach(tool -> all.put(tool, true));
    return all;
  }

  /** A copy of the current visibility, safe for the caller to read or serialise. */
  public synchronized Map<String, Boolean> visibility() {
    return new LinkedHashMap<>(visibility);
  }

  /**
   * Replaces the visibility with the administrator's choice. Only known tools are
   * taken from {@code incoming}; a missing tool stays visible, so a partial or
   * malformed request can never blank out the panel.
   */
  public synchronized void update(Map<String, Boolean> incoming) {
    Map<String, Boolean> next = defaults();
    if (incoming != null) {
      for (String tool : TOOLS) {
        Object value = incoming.get(tool);
        if (value instanceof Boolean chosen) {
          next.put(tool, chosen);
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
      for (String tool : TOOLS) {
        if (parsed.get(tool) instanceof Boolean chosen) {
          loaded.put(tool, chosen);
        }
      }
      return loaded;
    } catch (IOException | RuntimeException failure) {
      // A corrupt or unreadable file must not stop the UI from rendering; fall
      // back to showing everything, which is the safe, non-restricting default.
      log.warn("Could not read tool visibility from {}: {}", file, failure.toString());
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
      // Persistence is best-effort: the in-memory value already took effect, so
      // the choice holds until restart even if the file could not be written.
      log.warn("Could not persist tool visibility to {}: {}", file, failure.toString());
    }
  }
}
