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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kudos.ui.config.SparkRegistrationProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunningSparkApplicationsTests {

  private static RunningSparkApplications registry(Duration ttl, String... hosts) {
    return new RunningSparkApplications(
        new SparkRegistrationProperties("token", List.of(hosts), ttl));
  }

  private static RunningSparkApplication application(String appId, String uiUrl, long registeredAt) {
    return new RunningSparkApplication(
        appId, appId, "analyst", uiUrl, System.currentTimeMillis(), "3.5.6", null, registeredAt);
  }

  @Test
  void registeredApplicationIsFoundAndThenRemoved() {
    RunningSparkApplications registry = registry(Duration.ofHours(1), "*.test.local");
    registry.register(
        application("app-1", "http://kyuubi.test.local:4041", System.currentTimeMillis()));

    assertThat(registry.find("app-1")).isPresent();
    assertThat(registry.applications()).hasSize(1);
    assertThat(registry.applications().get(0).toSparkApplication().completed()).isFalse();

    registry.unregister("app-1");
    assertThat(registry.find("app-1")).isEmpty();
  }

  @Test
  void onlyAllowedHostsAreAccepted() {
    RunningSparkApplications registry = registry(Duration.ofHours(1), "*.test.local", "10.");

    registry.register(application("pod", "http://10.1.4.7:4040", System.currentTimeMillis()));
    assertThat(registry.find("pod")).isPresent();

    assertThatThrownBy(
            () ->
                registry.register(
                    application("evil", "http://169.254.169.254/latest", System.currentTimeMillis())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> registry.register(application("file", "file:///etc/passwd", System.currentTimeMillis())))
        .isInstanceOf(IllegalArgumentException.class);
    // A host that merely ends with the pattern's letters is not inside the domain.
    assertThatThrownBy(
            () ->
                registry.register(
                    application("lookalike", "http://nottest.local:4040", System.currentTimeMillis())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEntryNobodyRemovedAgesOut() {
    RunningSparkApplications registry = registry(Duration.ofMinutes(30), "*.test.local");
    long anHourAgo = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
    registry.register(application("killed", "http://kyuubi.test.local:4040", anHourAgo));

    assertThat(registry.find("killed")).isEmpty();
    assertThat(registry.applications()).isEmpty();
  }
}
