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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Runs one StarRocks connection per request with the Vault-provided service account. */
@Service
public class StarRocksService implements SqlEngine {

  private final ClusterProperties properties;
  private final SqlQueryHistory history;

  public StarRocksService(ClusterProperties properties, SqlQueryHistory history) {
    this.properties = properties;
    this.history = history;
  }

  @Override
  public String id() {
    return "starrocks";
  }

  @Override
  public String displayName() {
    return "StarRocks";
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
    // StarRocks has no Kerberos support; API audit retains the logged-in user identity.
    String historyId = history.start(id(), sql);
    try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
      QueryResult result = SqlResults.run(statement, sql, maxRows);
      List<String> logs = warnings(statement);
      history.finish(id(), historyId, logs);
      return new QueryResult(result.columns(), result.rows(), result.message(), logs);
    } catch (Exception error) {
      history.fail(id(), historyId, error);
      throw error;
    }
  }

  @Override
  public List<Map<String, Object>> query(String sql) throws Exception {
    // Deliberately outside KerberosExecutor: this engine uses its Vault service account.
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      return SqlResults.toRows(resultSet);
    }
  }

  Connection openConnection() throws Exception {
    if (properties.starrocksUrl() == null || properties.starrocksUrl().isBlank()) {
      throw new IllegalStateException("StarRocks JDBC URL is not configured");
    }
    Class.forName("org.mariadb.jdbc.Driver");
    return DriverManager.getConnection(properties.starrocksUrl());
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
