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
package com.kudos.ui.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How Spark drivers announce their live UI to this application.
 *
 * @param registrationToken shared secret the Spark plugin presents; blank turns
 *     the {@code /engine-api} transport off entirely.
 * @param uiHosts hosts whose UI may be proxied, as exact names, {@code *.suffix}
 *     patterns or IPv4 prefixes. The address is supplied by the caller, so an
 *     empty list accepts nothing rather than everything.
 * @param runningTtl how long a registration survives without being removed by
 *     the driver itself; the backstop for a driver that was killed.
 */
@ConfigurationProperties(prefix = "kudos.spark")
public record SparkRegistrationProperties(
    String registrationToken, List<String> uiHosts, Duration runningTtl) {

  public SparkRegistrationProperties {
    registrationToken = registrationToken == null ? "" : registrationToken.trim();
    uiHosts = uiHosts == null ? List.of() : List.copyOf(uiHosts);
    runningTtl = runningTtl == null ? Duration.ofHours(12) : runningTtl;
  }

  public boolean enabled() {
    return !registrationToken.isEmpty();
  }
}
