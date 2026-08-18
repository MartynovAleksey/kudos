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
package com.kudos.ui.api;

import com.kudos.ui.security.RoleAccess;
import com.kudos.ui.service.EngineVisibilityStore;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads and updates which SQL engines are shown in the editor. Reading is open to
 * any signed-in user; changing it is administrator-only, mirroring
 * {@link ToolVisibilityController}.
 */
@RestController
@RequestMapping("/ui-api/engine-visibility")
public class EngineVisibilityController {

  private final EngineVisibilityStore store;
  private final RoleAccess roles;

  public EngineVisibilityController(EngineVisibilityStore store, RoleAccess roles) {
    this.store = store;
    this.roles = roles;
  }

  @GetMapping
  Map<String, Boolean> visibility() {
    return store.visibility();
  }

  @PutMapping
  Map<String, Boolean> update(@RequestBody Map<String, Boolean> body, Authentication authentication) {
    if (!roles.isAdministrator(authentication)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    store.update(body);
    return store.visibility();
  }
}
