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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Resolves editor requests to fixed Spring-managed SQL engines. */
@Component
public class SqlEngineRegistry {

  private final Map<String, SqlEngine> byId;

  public SqlEngineRegistry(List<SqlEngine> engines) {
    Map<String, SqlEngine> registered = new LinkedHashMap<>();
    for (SqlEngine engine : engines) {
      String id = engineId(engine);
      SqlEngine previous = registered.putIfAbsent(id, engine);
      if (previous != null) {
        throw new IllegalStateException("Duplicate SQL engine id: " + engine.id());
      }
    }
    if (!registered.containsKey("kyuubi")) {
      throw new IllegalStateException("The default Kyuubi SQL engine is not registered");
    }
    byId = Collections.unmodifiableMap(new LinkedHashMap<>(registered));
  }

  public SqlEngine get(String id) {
    return byId.getOrDefault(normalize(id), byId.get("kyuubi"));
  }

  public List<SqlEngineInfo> available() {
    return byId.values().stream().map(SqlEngineRegistry::describe).toList();
  }

  private static SqlEngineInfo describe(SqlEngine engine) {
    return new SqlEngineInfo(
        engine.id(), engine.displayName(), engine.supportsSessions(), engine.supportsHistory());
  }

  private static String engineId(SqlEngine engine) {
    // The engine type is fixed by its Spring bean. This also keeps Mockito-replaced beans
    // distinguishable while an integration-test application context is being created.
    if (engine instanceof KyuubiFlinkService) {
      return "kyuubi-flink";
    }
    if (engine instanceof KyuubiService) {
      return "kyuubi";
    }
    return normalize(engine.id());
  }

  private static String normalize(String id) {
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }
}
