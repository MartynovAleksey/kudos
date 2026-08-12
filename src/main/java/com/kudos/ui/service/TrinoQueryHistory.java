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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** In-memory Trino query history isolated by authenticated KUDOS user. */
@Service
public class TrinoQueryHistory {

  private static final int MAX_ENTRIES = 100;

  private final Map<String, List<TrinoQueryInfo>> byUser = new ConcurrentHashMap<>();

  public String start(String statement) {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    update(
        entries -> {
          entries.addFirst(new TrinoQueryInfo(id, statement, "RUNNING", now, 0, "", List.of()));
          trim(entries);
        });
    return id;
  }

  public void finish(String id, List<String> logs) {
    replace(id, "FINISHED", "", logs);
  }

  public void fail(String id, Exception error) {
    replace(id, "ERROR", message(error), List.of());
  }

  public List<TrinoQueryInfo> entries() {
    return List.copyOf(byUser.getOrDefault(currentUser(), List.of()));
  }

  public String sql(String id) {
    return entries().stream()
        .filter(entry -> entry.id().equals(id))
        .findFirst()
        .map(TrinoQueryInfo::statement)
        .orElseThrow(() -> new IllegalArgumentException("No such Trino query"));
  }

  private void replace(String id, String state, String error, List<String> logs) {
    long now = System.currentTimeMillis();
    update(
        entries -> {
          for (int index = 0; index < entries.size(); index++) {
            TrinoQueryInfo entry = entries.get(index);
            if (entry.id().equals(id)) {
              entries.set(
                  index,
                  new TrinoQueryInfo(
                      entry.id(),
                      entry.statement(),
                      state,
                      entry.startedAtEpochMs(),
                      now,
                      error,
                      List.copyOf(logs)));
              return;
            }
          }
        });
  }

  private void update(java.util.function.Consumer<ArrayList<TrinoQueryInfo>> change) {
    byUser.compute(
        currentUser(),
        (ignored, current) -> {
          ArrayList<TrinoQueryInfo> entries = new ArrayList<>(current == null ? List.of() : current);
          change.accept(entries);
          return List.copyOf(entries);
        });
  }

  private static void trim(ArrayList<TrinoQueryInfo> entries) {
    entries.subList(Math.min(entries.size(), MAX_ENTRIES), entries.size()).clear();
  }

  private static String currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? "anonymous" : authentication.getName();
  }

  private static String message(Exception error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }
}
