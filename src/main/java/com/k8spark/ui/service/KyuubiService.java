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

package com.k8spark.ui.service;

import com.k8spark.ui.config.ClusterProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Runs Spark SQL through Kyuubi and manages each user's sessions.
 *
 * <p>A session is a JDBC connection held open with {@code
 * kyuubi.engine.share.level=CONNECTION} and the user's own Spark parameters, so
 * it is an independent engine. A user can start several, switch the active one
 * and stop or restart them, which is how the editor offers parallel sessions
 * with different Spark configuration. Queries run on the active session, or —
 * when there is none — on a throwaway connection with the cluster defaults.
 */
@Service
public class KyuubiService {

  private static final int MAX_SESSIONS_PER_USER = 5;

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;
  private final Map<String, UserSessions> byUser = new ConcurrentHashMap<>();

  public KyuubiService(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  // --------------------------------------------------------------- queries

  public List<Map<String, Object>> query(String sql) throws Exception {
    KyuubiSession session = activeSession();
    if (session != null) {
      synchronized (session) {
        try (Statement statement = session.connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)) {
          return toRows(resultSet);
        }
      }
    }
    return kerberos.asLoggedInUser(
        () -> {
          try (Connection connection = DriverManager.getConnection(properties.kyuubiUrl());
              Statement statement = connection.createStatement();
              ResultSet resultSet = statement.executeQuery(sql)) {
            return toRows(resultSet);
          }
        });
  }

  /** Same query path, shaped for the editor's result grid. */
  public QueryResult execute(String sql, int maxRows) throws Exception {
    KyuubiSession session = activeSession();
    if (session != null) {
      synchronized (session) {
        try (Statement statement = session.connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)) {
          return toResult(resultSet, maxRows);
        }
      }
    }
    return kerberos.asLoggedInUser(
        () -> {
          try (Connection connection = DriverManager.getConnection(properties.kyuubiUrl());
              Statement statement = connection.createStatement();
              ResultSet resultSet = statement.executeQuery(sql)) {
            return toResult(resultSet, maxRows);
          }
        });
  }

  // -------------------------------------------------------------- sessions

  public List<KyuubiSessionInfo> sessions() {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      return List.of();
    }
    synchronized (user) {
      List<KyuubiSessionInfo> list = new ArrayList<>();
      for (KyuubiSession session : user.sessions.values()) {
        list.add(session.toInfo(session.id.equals(user.activeId)));
      }
      return list;
    }
  }

  public KyuubiSessionInfo start(String name, String sparkParams) throws Exception {
    String username = currentUser();
    UserSessions user = byUser.computeIfAbsent(username, key -> new UserSessions());
    synchronized (user) {
      if (user.sessions.size() >= MAX_SESSIONS_PER_USER) {
        throw new IllegalStateException(
            "Session limit reached (" + MAX_SESSIONS_PER_USER + "); stop one first");
      }
    }
    // Open outside the lock: launching the engine can take tens of seconds.
    Connection connection = open(sparkParams);
    KyuubiSession session =
        new KyuubiSession(UUID.randomUUID().toString(), displayName(name), sparkParams, connection);
    synchronized (user) {
      user.sessions.put(session.id, session);
      if (user.activeId == null) {
        user.activeId = session.id;
      }
      return session.toInfo(session.id.equals(user.activeId));
    }
  }

  public void stop(String id) {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      return;
    }
    KyuubiSession removed;
    synchronized (user) {
      removed = user.sessions.remove(id);
      if (id.equals(user.activeId)) {
        user.activeId = user.sessions.keySet().stream().findFirst().orElse(null);
      }
    }
    close(removed);
  }

  public KyuubiSessionInfo restart(String id, String sparkParams) throws Exception {
    UserSessions user = byUser.get(currentUser());
    KyuubiSession existing;
    if (user != null) {
      synchronized (user) {
        existing = user.sessions.get(id);
      }
    } else {
      existing = null;
    }
    if (existing == null) {
      throw new IllegalStateException("No such session");
    }
    String params = sparkParams == null ? existing.sparkParams : sparkParams;
    Connection connection = open(params);
    close(existing);
    KyuubiSession restarted = new KyuubiSession(id, existing.name, params, connection);
    synchronized (user) {
      user.sessions.put(id, restarted);
      return restarted.toInfo(id.equals(user.activeId));
    }
  }

  public void activate(String id) {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      return;
    }
    synchronized (user) {
      if (user.sessions.containsKey(id)) {
        user.activeId = id;
      }
    }
  }

  // ------------------------------------------------------------- internals

  private KyuubiSession activeSession() {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      return null;
    }
    synchronized (user) {
      return user.activeId == null ? null : user.sessions.get(user.activeId);
    }
  }

  private Connection open(String sparkParams) throws Exception {
    String url = sessionUrl(sparkParams);
    return kerberos.asLoggedInUser(() -> DriverManager.getConnection(url));
  }

  /**
   * Appends CONNECTION share level and the user's Spark params to the base URL.
   * In a Hive JDBC URL the conf list after {@code ?} is semicolon-separated.
   */
  private String sessionUrl(String sparkParams) {
    StringBuilder confs = new StringBuilder("kyuubi.engine.share.level=CONNECTION");
    if (sparkParams != null) {
      for (String line : sparkParams.split("\\r?\\n")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int separator = trimmed.indexOf('=');
        if (separator < 0) {
          separator = trimmed.indexOf(' ');
        }
        if (separator <= 0) {
          continue;
        }
        String key = trimmed.substring(0, separator).trim();
        String value = trimmed.substring(separator + 1).trim();
        confs.append(';').append(key).append('=').append(value);
      }
    }
    return properties.kyuubiUrl() + "?" + confs;
  }

  private static String displayName(String name) {
    return name == null || name.isBlank() ? "session" : name.trim();
  }

  private static void close(KyuubiSession session) {
    if (session != null && session.connection != null) {
      try {
        session.connection.close();
      } catch (Exception ignored) {
        // The engine tears itself down when the connection drops; nothing to do.
      }
    }
  }

  private static String currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getName() == null) {
      throw new IllegalStateException("Not authenticated");
    }
    return authentication.getName();
  }

  private static List<Map<String, Object>> toRows(ResultSet resultSet) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    ResultSetMetaData metadata = resultSet.getMetaData();
    while (resultSet.next()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int index = 1; index <= metadata.getColumnCount(); index++) {
        row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
      }
      rows.add(row);
    }
    return rows;
  }

  private static QueryResult toResult(ResultSet resultSet, int maxRows) throws Exception {
    ResultSetMetaData metadata = resultSet.getMetaData();
    int columnCount = metadata.getColumnCount();
    List<String> columns = new ArrayList<>(columnCount);
    for (int index = 1; index <= columnCount; index++) {
      columns.add(metadata.getColumnLabel(index));
    }
    List<List<Object>> rows = new ArrayList<>();
    while (resultSet.next() && rows.size() < maxRows) {
      List<Object> row = new ArrayList<>(columnCount);
      for (int index = 1; index <= columnCount; index++) {
        row.add(resultSet.getObject(index));
      }
      rows.add(row);
    }
    return new QueryResult(columns, rows);
  }

  private static final class UserSessions {
    private final Map<String, KyuubiSession> sessions = new LinkedHashMap<>();
    private String activeId;
  }

  private static final class KyuubiSession {
    private final String id;
    private final String name;
    private final String sparkParams;
    private final long createdAt;
    private final Connection connection;

    private KyuubiSession(String id, String name, String sparkParams, Connection connection) {
      this.id = id;
      this.name = name;
      this.sparkParams = sparkParams;
      this.connection = connection;
      this.createdAt = System.currentTimeMillis();
    }

    private KyuubiSessionInfo toInfo(boolean active) {
      return new KyuubiSessionInfo(id, name, sparkParams, active, createdAt);
    }
  }
}
