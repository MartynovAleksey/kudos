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

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC result shaping shared by all SQL engines. */
public final class SqlResults {

  private SqlResults() {}

  public static QueryResult run(Statement statement, String sql, int maxRows) throws Exception {
    if (statement.execute(sql)) {
      try (ResultSet resultSet = statement.getResultSet()) {
        return toResult(resultSet, maxRows);
      }
    }
    int updateCount = statement.getUpdateCount();
    return QueryResult.ok(updateCount >= 0 ? updateCount + " row(s) affected" : "OK");
  }

  public static List<Map<String, Object>> toRows(ResultSet resultSet) throws Exception {
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

  public static QueryResult toResult(ResultSet resultSet, int maxRows) throws Exception {
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
}
