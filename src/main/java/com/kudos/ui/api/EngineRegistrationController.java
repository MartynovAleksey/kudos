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

import com.kudos.ui.service.RunningSparkApplication;
import com.kudos.ui.service.RunningSparkApplications;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where a Spark driver announces itself while it runs, so its live UI can be
 * opened from Jobs.
 *
 * <p>Its own transport: {@code /api} is a user-facing API that an operator can
 * switch off, and {@code /ui-api} only accepts a browser session. An engine has
 * neither identity, so this path is authorized by the shared registration token
 * instead (see {@code EngineTokenFilter}).
 */
@RestController
@RequestMapping(EngineRegistrationController.PATH)
public class EngineRegistrationController {

  static final String PATH = "/engine-api/spark/running";

  private final RunningSparkApplications running;

  public EngineRegistrationController(RunningSparkApplications running) {
    this.running = running;
  }

  /** What the KUDOS Spark plugin sends once its application id and UI are known. */
  public record Registration(
      @NotBlank String appId,
      String name,
      @NotBlank String user,
      @NotBlank String uiUrl,
      long startTimeMs,
      String sparkVersion,
      String sessionId) {}

  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void register(@Valid @RequestBody Registration registration) {
    try {
      running.register(
          new RunningSparkApplication(
              registration.appId(),
              registration.name() == null ? registration.appId() : registration.name(),
              registration.user(),
              registration.uiUrl(),
              registration.startTimeMs() > 0
                  ? registration.startTimeMs()
                  : System.currentTimeMillis(),
              registration.sparkVersion() == null ? "" : registration.sparkVersion(),
              registration.sessionId(),
              System.currentTimeMillis()));
    } catch (IllegalArgumentException rejected) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, rejected.getMessage());
    }
  }

  @DeleteMapping("/{appId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void unregister(@PathVariable String appId) {
    running.unregister(appId);
  }
}
