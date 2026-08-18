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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FeatureGateTests {

  private final FeatureGate gate =
      new FeatureGate(new FeaturesProperties(true, true, true, false, true));

  @Test
  void disabledModuleRejectsBothApiPrefixes() throws Exception {
    assertRejected("/api/hbase/tables");
    assertRejected("/ui-api/hbase/tables");
  }

  @Test
  void enabledModuleStillPassesBothApiPrefixes() throws Exception {
    assertAllowed("/api/hdfs/list");
    assertAllowed("/ui-api/hdfs/list");
  }

  @Test
  void enabledHbasePassesBothApiPrefixes() throws Exception {
    var enabledHbaseGate = new FeatureGate(new FeaturesProperties(true, true, true, true, true));
    assertAllowed(enabledHbaseGate, "/api/hbase/tables");
    assertAllowed(enabledHbaseGate, "/ui-api/hbase/scan");
  }

  private void assertRejected(String path) throws Exception {
    var request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    var response = new MockHttpServletResponse();

    assertThat(gate.preHandle(request, response, new Object())).isFalse();
    assertThat(response.getStatus()).isEqualTo(404);
  }

  private void assertAllowed(String path) throws Exception {
    assertAllowed(gate, path);
  }

  private static void assertAllowed(FeatureGate featureGate, String path) throws Exception {
    var request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);

    assertThat(featureGate.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
  }
}
