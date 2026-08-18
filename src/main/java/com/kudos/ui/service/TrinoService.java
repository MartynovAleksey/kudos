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

import com.kudos.ui.config.ClusterProperties;
import io.trino.jdbc.TrinoDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Runs one Trino JDBC connection per request under the user's Kerberos Subject. */
@Service
public class TrinoService implements SqlEngine {

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;
  private final SqlQueryHistory history;

  public TrinoService(
      ClusterProperties properties, KerberosExecutor kerberos, SqlQueryHistory history) {
    this.properties = properties;
    this.kerberos = kerberos;
    this.history = history;
  }

  @Override
  public String id() {
    return "trino";
  }

  @Override
  public String displayName() {
    return "Trino";
  }

  @Override
  public boolean supportsSessions() {
    return false;
  }

  @Override
  public boolean supportsHistory() {
    return true;
  }

  @Override
  public QueryResult execute(String sql, int maxRows) throws Exception {
    return kerberos.asLoggedInUser(
        () -> {
          String historyId = history.start(id(), sql);
          try (Connection connection = openConnection();
              Statement statement = connection.createStatement()) {
            QueryResult result = SqlResults.run(statement, sql, maxRows);
            List<String> logs = warnings(statement);
            history.finish(id(), historyId, logs);
            return new QueryResult(result.columns(), result.rows(), result.message(), logs);
          } catch (Exception error) {
            history.fail(id(), historyId, error);
            throw error;
          }
        });
  }

  @Override
  public List<Map<String, Object>> query(String sql) throws Exception {
    return kerberos.asLoggedInUser(
        () -> {
          try (Connection connection = openConnection();
              Statement statement = connection.createStatement();
              ResultSet resultSet = statement.executeQuery(sql)) {
            return SqlResults.toRows(resultSet);
          }
        });
  }

  Connection openConnection() throws Exception {
    if (properties.trinoUrl() == null || properties.trinoUrl().isBlank()) {
      throw new IllegalStateException("Trino JDBC URL is not configured");
    }
    // Explicit registration is needed from asynchronous worker threads too.
    Class.forName(TrinoDriver.class.getName());
    return DriverManager.getConnection(properties.trinoUrl());
  }

  private static List<String> warnings(Statement statement) {
    List<String> logs = new ArrayList<>();
    try {
      for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
        logs.add(warning.getMessage());
      }
    } catch (Exception ignored) {
      // Query results stay available even when the driver cannot expose optional warnings.
    }
    return List.copyOf(logs);
  }
}
