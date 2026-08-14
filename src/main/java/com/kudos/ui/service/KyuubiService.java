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

import com.kudos.ui.config.ClusterProperties;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedActionException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hive.jdbc.HiveConnection;
import org.apache.hive.jdbc.HiveDriver;
import org.apache.hive.jdbc.HiveStatement;
import org.apache.hive.service.cli.HandleIdentifier;
import org.apache.hive.service.rpc.thrift.TSessionHandle;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs Spark SQL through Kyuubi and manages each user's sessions. */
@Service
@Primary
public class KyuubiService implements KyuubiSqlEngine {

  private static final int MAX_SESSIONS_PER_USER = 5;
  private static final int MAX_MONITORED_OPERATIONS = 20;

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;
  private final KyuubiRestClient rest;
  private final SqlQueryHistory history;
  private final String engineId;
  private final String engineName;
  private final String engineType;
  private final Map<String, UserSessions> byUser = new ConcurrentHashMap<>();

  @Autowired
  public KyuubiService(
      ClusterProperties properties,
      KerberosExecutor kerberos,
      KyuubiRestClient rest,
      SqlQueryHistory history) {
    this(properties, kerberos, rest, history, "kyuubi", "Kyuubi Spark SQL", "SPARK_SQL");
  }

  /** Convenience constructor for focused tests outside Spring's application context. */
  public KyuubiService(ClusterProperties properties, KerberosExecutor kerberos, KyuubiRestClient rest) {
    this(properties, kerberos, rest, new SqlQueryHistory(), "kyuubi", "Kyuubi Spark SQL", "SPARK_SQL");
  }

  protected KyuubiService(
      ClusterProperties properties,
      KerberosExecutor kerberos,
      KyuubiRestClient rest,
      SqlQueryHistory history,
      String engineId,
      String engineName,
      String engineType) {
    this.properties = properties;
    this.kerberos = kerberos;
    this.rest = rest;
    this.history = history;
    this.engineId = engineId;
    this.engineName = engineName;
    this.engineType = engineType;
  }

  // --------------------------------------------------------------- queries

  @Override
  public String id() {
    return engineId;
  }

  @Override
  public String displayName() {
    return engineName;
  }

  @Override
  public boolean supportsSessions() {
    return true;
  }

  @Override
  public List<Map<String, Object>> query(String sql) throws Exception {
    String historyId = history.start(id(), sql);
    KyuubiSession session = activeSession();
    try {
      if (session != null) {
        synchronized (session) {
          try (Statement statement = session.requireConnection().createStatement();
              ResultSet resultSet = statement.executeQuery(sql)) {
            List<Map<String, Object>> rows = SqlResults.toRows(resultSet);
            history.finish(id(), historyId, queryLogs(statement));
            return rows;
          }
        }
      }
      return kerberos.asLoggedInUser(
          () -> {
            try (Connection connection = openKyuubiConnection(perQueryUrl());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
              List<Map<String, Object>> rows = SqlResults.toRows(resultSet);
              history.finish(id(), historyId, queryLogs(statement));
              return rows;
            }
          });
    } catch (Exception error) {
      history.fail(id(), historyId, error);
      throw error;
    }
  }

  /** Same query path, shaped for the editor's result grid. */
  @Override
  public QueryResult execute(String sql, int maxRows) throws Exception {
    String historyId = history.start(id(), sql);
    KyuubiSession session = activeSession();
    try {
      if (session != null) {
        synchronized (session) {
          String operationId = null;
          try (Statement statement = session.requireConnection().createStatement()) {
            operationId = session.beginOperation(sql);
            QueryResult result = SqlResults.run(statement, sql, maxRows);
            session.finishOperation(operationId, statement, null);
            QueryResult withLogs =
                new QueryResult(result.columns(), result.rows(), result.message(), queryLogs(statement));
            history.finish(id(), historyId, withLogs.logs());
            session.rememberResult(withLogs);
            return withLogs;
          } catch (Exception error) {
            if (operationId != null) {
              session.finishOperation(operationId, null, error);
            }
            throw error;
          }
        }
      }
      return kerberos.asLoggedInUser(
          () -> {
            try (Connection connection = openKyuubiConnection(perQueryUrl());
                Statement statement = connection.createStatement()) {
              QueryResult result = SqlResults.run(statement, sql, maxRows);
              QueryResult withLogs =
                  new QueryResult(result.columns(), result.rows(), result.message(), queryLogs(statement));
              history.finish(id(), historyId, withLogs.logs());
              return withLogs;
            }
          });
    } catch (Exception error) {
      history.fail(id(), historyId, error);
      throw error;
    }
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

  /** Exposes this user's live Kyuubi engines to the Jobs Running section. */
  public List<SparkApplication> runningApplications() {
    String user = currentUser();
    UserSessions sessions = byUser.get(user);
    return sessions == null ? List.of() : activeApplications(user, sessions);
  }

  /** Exposes every user's live Kyuubi engines for the administrator's Jobs screen. */
  public List<SparkApplication> runningApplicationsForAllUsers() {
    List<SparkApplication> applications = new ArrayList<>();
    byUser.forEach((user, sessions) -> applications.addAll(activeApplications(user, sessions)));
    return List.copyOf(applications);
  }

  /** Adds a tab immediately, then opens its Kyuubi connection in the background. */
  public KyuubiSessionInfo start(String name, String sparkParams) {
    String username = currentUser();
    Authentication authentication = currentAuthentication();
    UserSessions user = byUser.computeIfAbsent(username, key -> new UserSessions());
    KyuubiSession session = new KyuubiSession(UUID.randomUUID().toString(), displayName(name), sparkParams);
    synchronized (user) {
      if (user.sessions.size() >= MAX_SESSIONS_PER_USER) {
        throw new IllegalStateException(
            "Session limit reached (" + MAX_SESSIONS_PER_USER + "); stop one first");
      }
      user.sessions.put(session.id, session);
      user.activeId = session.id;
    }
    launch(session, authentication);
    return session.toInfo(true);
  }

  public void stop(String id) {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      throw new IllegalStateException("No such session");
    }
    KyuubiSession removed;
    synchronized (user) {
      removed = user.sessions.remove(id);
      if (id.equals(user.activeId)) {
        user.activeId = user.sessions.keySet().stream().findFirst().orElse(null);
      }
    }
    if (removed == null) {
      throw new IllegalStateException("No such session");
    }
    closeAsync(removed);
  }

  /** Restarts the engine while keeping the browser tab and its saved query. */
  public KyuubiSessionInfo restart(String id, String sparkParams) {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      throw new IllegalStateException("No such session");
    }
    Authentication authentication = currentAuthentication();
    KyuubiSession restarted;
    KyuubiSession existing;
    synchronized (user) {
      existing = user.sessions.get(id);
      if (existing == null) {
        throw new IllegalStateException("No such session");
      }
      String params = sparkParams == null ? existing.sparkParams : sparkParams;
      restarted = new KyuubiSession(id, existing.name, params);
      user.sessions.put(id, restarted);
    }
    close(existing);
    launch(restarted, authentication);
    return restarted.toInfo(id.equals(user.activeId));
  }

  @Override
  public QueryResult lastResult(String id) {
    KyuubiSession session = session(id);
    return session == null ? null : session.lastResult();
  }

  @Override
  public void clearResult(String id) {
    KyuubiSession session = session(id);
    if (session == null) {
      throw new IllegalArgumentException("No such session");
    }
    session.clearResult();
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

  @Override
  public void deactivate() {
    UserSessions user = byUser.get(currentUser());
    if (user != null) {
      synchronized (user) {
        user.activeId = null;
      }
    }
  }

  public KyuubiSessionMonitor monitor(String id) {
    KyuubiSession session = session(id);
    if (session == null) {
      throw new IllegalStateException("No such session");
    }
    if (session.kyuubiSessionId == null) {
      return session.monitor(null, null);
    }
    try {
      KyuubiRestClient.Snapshot snapshot = rest.monitor(session.kyuubiSessionId);
      session.lastSnapshot = snapshot;
      return session.monitor(snapshot, null);
    } catch (Exception error) {
      return session.monitor(session.lastSnapshot, message(error));
    }
  }

  /** Repeats a saved operation in its owning session, which becomes active first. */
  public QueryResult executeOperation(String sessionId, String operationId, int maxRows) throws Exception {
    KyuubiSession session = session(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("No such session");
    }
    String statement = session.operationStatement(operationId);
    activate(sessionId);
    return execute(statement, maxRows);
  }

  public String operationSql(String sessionId, String operationId) {
    KyuubiSession session = session(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("No such session");
    }
    return session.operationStatement(operationId);
  }

  // ------------------------------------------------------------- internals

  private void launch(KyuubiSession session, Authentication authentication) {
    CompletableFuture.runAsync(
        () -> {
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(authentication);
          SecurityContextHolder.setContext(context);
          try {
            open(session);
          } finally {
            SecurityContextHolder.clearContext();
          }
        });
  }

  private void open(KyuubiSession session) {
    String engineId = UUID.randomUUID().toString();
    try {
      Connection connection =
          kerberos.asLoggedInUser(
              () -> openKyuubiConnection(sessionUrl(session.sparkParams, engineId)));
      session.attach(connection, kyuubiSessionId(connection), engineName);
      if (session.stopped) {
        close(session);
        return;
      }
      try (Statement statement = connection.createStatement();
          ResultSet ignored = statement.executeQuery("SELECT 1")) {
        ignored.next();
        session.updateLogs(queryLogs(statement));
        session.ready();
      }
    } catch (Exception error) {
      session.failed(error);
      close(session);
    }
  }

  private KyuubiSession activeSession() {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      return null;
    }
    synchronized (user) {
      return user.activeId == null ? null : user.sessions.get(user.activeId);
    }
  }

  private static List<SparkApplication> activeApplications(String user, UserSessions sessions) {
    synchronized (sessions) {
      return sessions.sessions.values().stream()
          .filter(session -> session.state != State.FAILED)
          .map(
              session ->
                  new SparkApplication(
                      "kyuubi-" + session.id,
                      session.name,
                      user,
                      Instant.ofEpochMilli(session.createdAt).toString(),
                      "",
                      Math.max(0, System.currentTimeMillis() - session.createdAt),
                      false,
                      "Kyuubi engine"))
          .toList();
    }
  }

  private KyuubiSession session(String id) {
    UserSessions user = byUser.get(currentUser());
    if (user == null) {
      return null;
    }
    synchronized (user) {
      return user.sessions.get(id);
    }
  }

  /** The common-pool worker does not inherit Spring Boot's class loader. */
  private static Connection openKyuubiConnection(String url) throws Exception {
    Class.forName(HiveDriver.class.getName());
    return DriverManager.getConnection(url);
  }

  /** Appends CONNECTION share level and the user's Spark params to the base URL. */
  private String sessionUrl(String sparkParams, String engineId) {
    StringBuilder confs =
        new StringBuilder("kyuubi.engine.share.level=CONNECTION;kyuubi.engine.type=").append(engineType);
    boolean driverOptionsSet = false;
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
        if ("SPARK_SQL".equals(engineType) && key.equals("spark.driver.extraJavaOptions")) {
          value += " -Dderby.system.home=/tmp/kudos-metastore-" + engineId;
          driverOptionsSet = true;
        }
        confs.append(';').append(key).append('=').append(value);
      }
    }
    if ("SPARK_SQL".equals(engineType) && !driverOptionsSet) {
      confs
          .append(";spark.driver.extraJavaOptions=-Dderby.system.home=/tmp/kudos-metastore-")
          .append(engineId);
    }
    return properties.kyuubiUrl() + "?" + confs;
  }

  private String perQueryUrl() {
    return sessionUrl("", UUID.randomUUID().toString());
  }

  private static String kyuubiSessionId(Connection connection) throws Exception {
    HiveConnection hive =
        connection instanceof HiveConnection current
            ? current
            : connection.unwrap(HiveConnection.class);
    Field field = HiveConnection.class.getDeclaredField("sessHandle");
    field.setAccessible(true);
    TSessionHandle handle = (TSessionHandle) field.get(hive);
    return new HandleIdentifier(handle.getSessionId()).getPublicId().toString();
  }

  private static String displayName(String name) {
    return name == null || name.isBlank() ? "session" : name.trim();
  }

  private static void close(KyuubiSession session) {
    if (session == null) {
      return;
    }
    session.stopped = true;
    session.stopping();
    closeConnection(session);
  }

  private static void closeAsync(KyuubiSession session) {
    if (session == null) {
      return;
    }
    session.stopped = true;
    session.stopping();
    CompletableFuture.runAsync(() -> closeConnection(session));
  }

  private static void closeConnection(KyuubiSession session) {
    Connection connection = session.connection;
    if (connection != null) {
      try {
        connection.close();
      } catch (Exception ignored) {
        // The engine tears itself down when the connection drops; nothing to do.
      }
    }
  }

  private static Authentication currentAuthentication() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getName() == null) {
      throw new IllegalStateException("Not authenticated");
    }
    return authentication;
  }

  private static String currentUser() {
    return currentAuthentication().getName();
  }

  private static String message(Exception error) {
    Throwable cause = error;
    while ((cause instanceof UndeclaredThrowableException || cause instanceof PrivilegedActionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  private static int count(List<KyuubiOperationInfo> operations, String... states) {
    int result = 0;
    for (KyuubiOperationInfo operation : operations) {
      String current = operation.state().toUpperCase(Locale.ROOT).replace("_STATE", "");
      for (String state : states) {
        if (current.equals(state)) {
          result += operation.executionCount();
          break;
        }
      }
    }
    return result;
  }

  private static List<String> queryLogs(Statement statement) {
    if (!(statement instanceof HiveStatement hive)) {
      return List.of();
    }
    try {
      return List.copyOf(hive.getQueryLog(false, 200));
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static final class UserSessions {
    private final Map<String, KyuubiSession> sessions = new LinkedHashMap<>();
    private String activeId;
  }

  private static final class KyuubiSession {
    private final String id;
    private final String name;
    private final String sparkParams;
    private final long createdAt = System.currentTimeMillis();
    private volatile String kyuubiSessionId;
    private volatile Connection connection;
    private volatile State state = State.STARTING;
    private volatile String message = "Waiting to open a Kyuubi session";
    private volatile boolean stopped;
    private volatile KyuubiRestClient.Snapshot lastSnapshot;
    private volatile List<KyuubiOperationInfo> operations = List.of();
    private volatile List<String> logs = List.of();
    private volatile QueryResult lastResult;

    private KyuubiSession(String id, String name, String sparkParams) {
      this.id = id;
      this.name = name;
      this.sparkParams = sparkParams;
    }

    private void attach(Connection connection, String kyuubiSessionId, String engineName) {
      this.connection = connection;
      this.kyuubiSessionId = kyuubiSessionId;
      this.state = State.ENGINE_STARTING;
      this.message = "Kyuubi session opened; starting " + engineName + " engine";
    }

    private void ready() {
      if (!stopped) {
        state = State.READY;
        message = "Ready";
      }
    }

    private void failed(Exception error) {
      if (!stopped) {
        state = State.FAILED;
        message = KyuubiService.message(error);
      }
    }

    private void stopping() {
      if (state != State.FAILED) {
        state = State.STOPPING;
        message = "Closing";
      }
    }

    private Connection requireConnection() {
      if (state != State.READY || connection == null) {
        throw new IllegalStateException("Session is " + state + ": " + message);
      }
      return connection;
    }

    private String beginOperation(String sql) {
      String operationId = UUID.randomUUID().toString();
      long now = System.currentTimeMillis();
      List<KyuubiOperationInfo> updated = new ArrayList<>();
      updated.add(
          new KyuubiOperationInfo(
              operationId,
              sql,
              "RUNNING_STATE",
              now,
                  now,
                  0,
                  "",
                  1,
                  Map.of(),
              List.of(),
              List.of()));
      for (int index = 0;
          index < operations.size() && updated.size() < MAX_MONITORED_OPERATIONS;
          index++) {
        updated.add(operations.get(index));
      }
      operations = List.copyOf(updated);
      return operationId;
    }

    private void finishOperation(String operationId, Statement statement, Exception error) {
      List<KyuubiOperationInfo> updated = new ArrayList<>(operations.size());
      long now = System.currentTimeMillis();
      for (KyuubiOperationInfo operation : operations) {
        if (operation.id().equals(operationId)) {
          updated.add(
              new KyuubiOperationInfo(
                  operation.id(),
                  operation.statement(),
                  error == null ? "FINISHED_STATE" : "ERROR_STATE",
                  operation.createdAtEpochMs(),
                  operation.startedAtEpochMs(),
                  now,
                  error == null ? "" : message(error),
                  operation.executionCount(),
                  operation.metrics(),
                  operation.progressHeaders(),
                  operation.progressRows()));
        } else {
          updated.add(operation);
        }
      }
      operations = compactConsecutive(updated);
      updateLogs(queryLogs(statement));
    }

    private String operationStatement(String operationId) {
      return operations.stream()
          .filter(operation -> operation.id().equals(operationId))
          .findFirst()
          .map(KyuubiOperationInfo::statement)
          .orElseThrow(() -> new IllegalArgumentException("No such operation"));
    }

    private static List<KyuubiOperationInfo> compactConsecutive(
        List<KyuubiOperationInfo> operations) {
      if (operations.size() < 2) {
        return List.copyOf(operations);
      }
      KyuubiOperationInfo latest = operations.getFirst();
      KyuubiOperationInfo previous = operations.get(1);
      if ("RUNNING_STATE".equals(latest.state())
          || !normalize(latest.statement()).equals(normalize(previous.statement()))) {
        return List.copyOf(operations);
      }
      List<KyuubiOperationInfo> compacted = new ArrayList<>(operations);
      compacted.set(
          0,
          new KyuubiOperationInfo(
              latest.id(),
              latest.statement(),
              latest.state(),
              latest.createdAtEpochMs(),
              latest.startedAtEpochMs(),
              latest.completedAtEpochMs(),
              latest.error(),
              latest.executionCount() + previous.executionCount(),
              latest.metrics(),
              latest.progressHeaders(),
              latest.progressRows()));
      compacted.remove(1);
      return List.copyOf(compacted);
    }

    private static String normalize(String sql) {
      return sql == null ? "" : sql.trim().replaceAll("\\s+", " ");
    }

    private void updateLogs(List<String> fetched) {
      if (!fetched.isEmpty()) {
        logs = fetched;
      }
    }

    private QueryResult lastResult() {
      return lastResult;
    }

    private void rememberResult(QueryResult result) {
      lastResult = result;
    }

    private void clearResult() {
      lastResult = null;
    }

    private KyuubiSessionInfo toInfo(boolean active) {
      return new KyuubiSessionInfo(
          id, name, sparkParams, active, createdAt, kyuubiSessionId, state.name(), message);
    }

    private KyuubiSessionMonitor monitor(KyuubiRestClient.Snapshot snapshot, String monitoringError) {
      List<KyuubiOperationInfo> currentOperations = operations;
      return new KyuubiSessionMonitor(
          id,
          state.name(),
          message,
          kyuubiSessionId,
          snapshot == null ? null : snapshot.engineId(),
          snapshot == null ? null : snapshot.engineName(),
          snapshot == null ? null : snapshot.engineUrl(),
          snapshot == null ? 0 : snapshot.openedAtEpochMs(),
          snapshot == null
              ? executionCount(currentOperations)
              : Math.max(snapshot.totalOperations(), executionCount(currentOperations)),
          count(currentOperations, "PENDING"),
          count(currentOperations, "RUNNING"),
          count(currentOperations, "FINISHED"),
          count(currentOperations, "ERROR", "CANCELED"),
          snapshot == null ? 0 : snapshot.executorPoolSize(),
          snapshot == null ? 0 : snapshot.executorPoolActiveCount(),
          snapshot == null ? 0 : snapshot.executorPoolQueueSize(),
          currentOperations,
          logs,
          monitoringError);
    }

    private static int executionCount(List<KyuubiOperationInfo> operations) {
      return operations.stream().mapToInt(KyuubiOperationInfo::executionCount).sum();
    }
  }

  private enum State {
    STARTING,
    ENGINE_STARTING,
    READY,
    STOPPING,
    FAILED
  }
}
