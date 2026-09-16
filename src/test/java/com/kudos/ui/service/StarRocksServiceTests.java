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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kudos.ui.config.ClusterProperties;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class StarRocksServiceTests {

  @Test
  void executesSelectAndNonSelectStatementsThroughTheSharedResultMapper() throws Exception {
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute("SELECT 42")).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("answer");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject(1)).thenReturn(42);

    StarRocksService service = service(connection);
    assertThat(service.execute("SELECT 42", 10))
        .isEqualTo(new QueryResult(java.util.List.of("answer"), java.util.List.of(java.util.List.of(42)), null));

    when(statement.execute("USE kudos_test")).thenReturn(false);
    when(statement.getUpdateCount()).thenReturn(-1);
    assertThat(service.execute("USE kudos_test", 10).message()).isEqualTo("OK");
  }

  private static StarRocksService service(Connection connection) {
    return new StarRocksService(
        new ClusterProperties("", "", "", "", "jdbc:mariadb://test", "", "", "", "", "", "", "", "", ""),
        new SqlQueryHistory()) {
      @Override
      Connection openConnection() {
        return connection;
      }
    };
  }
}
