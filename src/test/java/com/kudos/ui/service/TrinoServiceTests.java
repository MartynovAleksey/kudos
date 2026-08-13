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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kudos.ui.config.ClusterProperties;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLWarning;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class TrinoServiceTests {

  @Test
  void executesSelectAndNonSelectStatementsThroughTheSharedResultMapper() throws Exception {
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute("SELECT 1")).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("one");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject(1)).thenReturn(1);
    when(statement.getWarnings()).thenReturn(new SQLWarning("Trino warning"));

    TrinoService service = service(connection);
    assertThat(service.execute("SELECT 1", 10))
        .isEqualTo(
            new QueryResult(
                java.util.List.of("one"),
                java.util.List.of(java.util.List.of(1)),
                null,
                java.util.List.of("Trino warning")));

    when(statement.execute("USE tpch.tiny")).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(-1);
    assertThat(service.execute("USE tpch.tiny", 10).message()).isEqualTo("OK");
  }

  private static TrinoService service(Connection connection) {
    KerberosExecutor kerberos =
        new KerberosExecutor() {
          @Override
          public <T> T asLoggedInUser(PrivilegedExceptionAction<T> action) throws Exception {
            return action.run();
          }
        };
    return new TrinoService(
        new ClusterProperties("", "", "", "jdbc:trino://test", "", "", "", "", "", "", "", ""),
        kerberos,
        new SqlQueryHistory()) {
      @Override
      Connection openConnection() {
        return connection;
      }
    };
  }
}
