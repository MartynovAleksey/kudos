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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kudos.ui.api.EngineRegistrationController.Registration;
import com.kudos.ui.config.SparkRegistrationProperties;
import com.kudos.ui.service.RunningSparkApplications;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class EngineRegistrationControllerTests {

  private final RunningSparkApplications running =
      new RunningSparkApplications(
          new SparkRegistrationProperties("token", List.of("*.test.local"), Duration.ofHours(1)));
  private final EngineRegistrationController controller = new EngineRegistrationController(running);

  @Test
  void anEngineRegistersAndUnregistersItself() {
    controller.register(
        new Registration(
            "app-1", null, "analyst", "http://kyuubi.test.local:4040", 0, null, "session-7"));

    assertThat(running.find("app-1")).isPresent();
    // A driver that reported no name is still identifiable in the Jobs list.
    assertThat(running.find("app-1").orElseThrow().name()).isEqualTo("app-1");
    assertThat(running.find("app-1").orElseThrow().sessionId()).isEqualTo("session-7");
    // No start time reported means "now", not 1970.
    assertThat(running.find("app-1").orElseThrow().startTimeMs()).isPositive();

    controller.unregister("app-1");
    assertThat(running.find("app-1")).isEmpty();
  }

  @Test
  void anAddressOutsideTheAllowlistIsRefused() {
    assertThatThrownBy(
            () ->
                controller.register(
                    new Registration(
                        "app-2", "job", "analyst", "http://evil.example.org/", 0, null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(running.applications()).isEmpty();
  }
}
