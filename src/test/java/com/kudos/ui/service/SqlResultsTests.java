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

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class SqlResultsTests {

  @Test
  void shapesAResultSetAndAcknowledgesStatementsWithoutOne() throws Exception {
    Statement select = mock(Statement.class);
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(select.execute("SELECT 42")).thenReturn(true);
    when(select.getResultSet()).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("answer");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject(1)).thenReturn(42);

    assertThat(SqlResults.run(select, "SELECT 42", 10))
        .isEqualTo(new QueryResult(java.util.List.of("answer"), java.util.List.of(java.util.List.of(42))));

    Statement update = mock(Statement.class);
    when(update.execute("USE tpch.tiny")).thenReturn(false);
    when(update.getUpdateCount()).thenReturn(-1);
    assertThat(SqlResults.run(update, "USE tpch.tiny", 10).message()).isEqualTo("OK");
  }
}
