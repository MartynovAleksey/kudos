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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** In-memory per-user query history for SQL engines. */
@Service
public class SqlQueryHistory {

  private static final int MAX_ENTRIES = 100;

  private final Map<String, List<SqlQueryInfo>> byUserAndEngine = new ConcurrentHashMap<>();

  public String start(String engine, String statement) {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    update(engine, entries -> entries.addFirst(new SqlQueryInfo(id, statement, "RUNNING", now, 0, "", 1, List.of())));
    return id;
  }

  public void finish(String engine, String id, List<String> logs) {
    replace(engine, id, "FINISHED", "", logs);
  }

  public void fail(String engine, String id, Exception error) {
    replace(engine, id, "ERROR", message(error), List.of());
  }

  public List<SqlQueryInfo> entries(String engine) {
    return List.copyOf(byUserAndEngine.getOrDefault(key(engine), List.of()));
  }

  /** Lists every user's history for an administrator-only aggregate screen. */
  public List<OwnedEntry> entriesForAllUsers(String engine) {
    String suffix = "\u0000" + engine;
    return byUserAndEngine.entrySet().stream()
        .filter(entry -> entry.getKey().endsWith(suffix))
        .flatMap(
            entry ->
                entry.getValue().stream()
                    .map(
                        query ->
                            new OwnedEntry(
                                entry.getKey().substring(0, entry.getKey().length() - suffix.length()),
                                query)))
        .toList();
  }

  public String sql(String engine, String id) {
    return entries(engine).stream()
        .filter(entry -> entry.id().equals(id))
        .findFirst()
        .map(SqlQueryInfo::statement)
        .orElseThrow(() -> new IllegalArgumentException("No such query"));
  }

  private void replace(String engine, String id, String state, String error, List<String> logs) {
    long now = System.currentTimeMillis();
    update(
        engine,
        entries -> {
          for (int index = 0; index < entries.size(); index++) {
            SqlQueryInfo entry = entries.get(index);
            if (entry.id().equals(id)) {
              entries.set(
                  index,
                  new SqlQueryInfo(
                      entry.id(), entry.statement(), state, entry.startedAtEpochMs(), now, error,
                      entry.executionCount(), List.copyOf(logs)));
              compactLatest(entries);
              return;
            }
          }
        });
  }

  private void update(String engine, java.util.function.Consumer<ArrayList<SqlQueryInfo>> change) {
    byUserAndEngine.compute(
        key(engine),
        (ignored, current) -> {
          ArrayList<SqlQueryInfo> entries = new ArrayList<>(current == null ? List.of() : current);
          change.accept(entries);
          entries.subList(Math.min(entries.size(), MAX_ENTRIES), entries.size()).clear();
          return List.copyOf(entries);
        });
  }

  private static void compactLatest(ArrayList<SqlQueryInfo> entries) {
    if (entries.size() < 2) {
      return;
    }
    SqlQueryInfo latest = entries.getFirst();
    SqlQueryInfo previous = entries.get(1);
    if ("RUNNING".equals(latest.state()) || !normalize(latest.statement()).equals(normalize(previous.statement()))) {
      return;
    }
    entries.set(
        0,
        new SqlQueryInfo(
            latest.id(), latest.statement(), latest.state(), latest.startedAtEpochMs(),
            latest.completedAtEpochMs(), latest.error(), latest.executionCount() + previous.executionCount(),
            latest.logs()));
    entries.remove(1);
  }

  private static String key(String engine) {
    return currentUser() + "\u0000" + engine;
  }

  private static String normalize(String sql) {
    return sql == null ? "" : sql.trim().replaceAll("\\s+", " ");
  }

  private static String currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? "anonymous" : authentication.getName();
  }

  private static String message(Exception error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  /** A query paired with its owner for cross-user administrative views. */
  public record OwnedEntry(String user, SqlQueryInfo query) {}
}
