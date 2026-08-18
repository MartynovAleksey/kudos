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

/** SQL engine backed by user-managed Kyuubi sessions. */
public interface KyuubiSqlEngine extends SqlEngine {

  List<KyuubiSessionInfo> sessions();

  KyuubiSessionMonitor monitor(String id);

  KyuubiSessionInfo start(String name, String engineParams);

  void stop(String id);

  KyuubiSessionInfo restart(String id, String engineParams);

  QueryResult lastResult(String id);

  void clearResult(String id);

  void activate(String id);

  void deactivate();

  QueryResult executeOperation(String sessionId, String operationId, int maxRows) throws Exception;

  String operationSql(String sessionId, String operationId);
}
