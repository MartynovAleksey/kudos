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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kudos.ui.config.ClusterProperties;
import com.kudos.ui.security.RoleAccess;
import com.kudos.ui.service.SparkApplication;
import com.kudos.ui.service.SparkApplicationAccessService;
import com.kudos.ui.service.SparkHistoryService;
import com.kudos.ui.service.FlinkService;
import com.kudos.ui.service.KyuubiFlinkService;
import com.kudos.ui.service.KyuubiService;
import com.kudos.ui.service.SqlQueryHistory;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SparkUiProxyControllerTests {

  private final SparkHistoryService history = mock(SparkHistoryService.class);
  private SparkUiProxyController controller;

  private SparkUiProxyController controller() throws Exception {
    HttpURLConnection upstream = mock(HttpURLConnection.class);
    when(upstream.getResponseCode()).thenReturn(200);
    when(upstream.getInputStream())
        .thenReturn(new ByteArrayInputStream("spark-ui".getBytes(StandardCharsets.UTF_8)));
    var access =
        new SparkApplicationAccessService(
            history,
            mock(KyuubiService.class),
            mock(KyuubiFlinkService.class),
            mock(SqlQueryHistory.class),
            mock(FlinkService.class),
            new RoleAccess());
    return new SparkUiProxyController(
        new ClusterProperties(
            "", "", "", "", "", "", "", "", "http://spark-history", "", "", ""), access) {
      @Override
      HttpURLConnection open(URL target) {
        return upstream;
      }
    };
  }

  @Test
  void userCanOpenOwnApplicationAndItsStaticResourcesButNotCatalogOrForeignApplication()
      throws Exception {
    controller = controller();
    when(history.application("own"))
        .thenReturn(Optional.of(new SparkApplication("own", "own", "analyst", "", "", 0, false, "4")));
    when(history.application("foreign"))
        .thenReturn(Optional.of(new SparkApplication("foreign", "foreign", "admin", "", "", 0, true, "4")));

    MockHttpServletResponse own = request("/spark-ui/history/own/jobs/");
    assertThat(own.getStatus()).isEqualTo(200);
    assertThat(own.getContentAsString()).isEqualTo("spark-ui");

    MockHttpServletResponse foreign = request("/spark-ui/history/foreign/jobs/");
    assertThat(foreign.getStatus()).isEqualTo(403);

    MockHttpServletResponse root = request("/spark-ui/");
    assertThat(root.getStatus()).isEqualTo(403);

    MockHttpServletResponse stylesheet = request("/spark-ui/static/bootstrap.min.css");
    assertThat(stylesheet.getStatus()).isEqualTo(200);
  }

  @Test
  void administratorCanOpenSparkCatalog() throws Exception {
    controller = controller();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/spark-ui/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.proxy(request, response, administrator());

    assertThat(response.getStatus()).isEqualTo(200);
    verifyNoInteractions(history);
  }

  private MockHttpServletResponse request(String uri) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.proxy(request, response, user());
    return response;
  }

  private static UsernamePasswordAuthenticationToken user() {
    return UsernamePasswordAuthenticationToken.authenticated(
        "analyst", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }

  private static UsernamePasswordAuthenticationToken administrator() {
    return UsernamePasswordAuthenticationToken.authenticated(
        "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")));
  }
}
