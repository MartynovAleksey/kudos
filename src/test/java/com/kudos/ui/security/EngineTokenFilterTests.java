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
package com.kudos.ui.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.kudos.ui.config.SparkRegistrationProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EngineTokenFilterTests {

  private static final String PATH = "/engine-api/spark/running";

  @Test
  void theConfiguredTokenPassesAndAnythingElseDoesNot() throws Exception {
    EngineTokenFilter filter =
        new EngineTokenFilter(
            new SparkRegistrationProperties("secret", List.of("*.test.local"), Duration.ofHours(1)));

    assertThat(status(filter, "Bearer secret")).isEqualTo(200);
    assertThat(passedThrough(filter, "Bearer secret")).isTrue();
    assertThat(status(filter, "Bearer wrong")).isEqualTo(401);
    assertThat(status(filter, null)).isEqualTo(401);
    assertThat(status(filter, "Basic secret")).isEqualTo(401);
  }

  @Test
  void withoutATokenTheTransportDoesNotExist() throws Exception {
    EngineTokenFilter filter =
        new EngineTokenFilter(new SparkRegistrationProperties("  ", null, null));

    assertThat(status(filter, "Bearer secret")).isEqualTo(404);
    assertThat(passedThrough(filter, "Bearer secret")).isFalse();
  }

  private static int status(EngineTokenFilter filter, String authorization) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request(authorization), response, new MockFilterChain());
    return response.getStatus();
  }

  private static boolean passedThrough(EngineTokenFilter filter, String authorization)
      throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request(authorization), new MockHttpServletResponse(), chain);
    return chain.getRequest() != null;
  }

  private static MockHttpServletRequest request(String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    return request;
  }
}
