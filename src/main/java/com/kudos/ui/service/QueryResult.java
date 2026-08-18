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

import java.util.List;

/**
 * Column-oriented SQL result, shaped for the editor's result grid. A statement
 * that produces no result set (DDL/DML/SET) carries no columns/rows and a
 * {@code message} acknowledgement instead ("OK" / "N row(s) affected").
 */
public record QueryResult(List<String> columns, List<List<Object>> rows, String message, List<String> logs) {

  public QueryResult(List<String> columns, List<List<Object>> rows) {
    this(columns, rows, null, List.of());
  }

  public QueryResult(List<String> columns, List<List<Object>> rows, String message) {
    this(columns, rows, message, List.of());
  }

  /** Acknowledgement for a statement with no result set. */
  public static QueryResult ok(String message) {
    return new QueryResult(List.of(), List.of(), message, List.of());
  }
}
