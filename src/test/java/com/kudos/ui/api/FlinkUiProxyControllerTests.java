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

package com.kudos.ui.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kudos.ui.config.ClusterProperties;
import com.kudos.ui.security.RoleAccess;
import com.kudos.ui.service.FlinkService;
import com.kudos.ui.service.KyuubiService;
import com.kudos.ui.service.SparkApplicationAccessService;
import com.kudos.ui.service.SparkHistoryService;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class FlinkUiProxyControllerTests {

  private FlinkUiProxyController controller() throws Exception {
    HttpURLConnection upstream = mock(HttpURLConnection.class);
    Mockito.when(upstream.getResponseCode()).thenReturn(200);
    Mockito.when(upstream.getInputStream())
        .thenReturn(new ByteArrayInputStream("flink-ui".getBytes(StandardCharsets.UTF_8)));
    return controllerWith(upstream);
  }

  private FlinkUiProxyController controllerWith(HttpURLConnection upstream) {
    var access =
        new SparkApplicationAccessService(
            mock(SparkHistoryService.class),
            mock(KyuubiService.class),
            mock(FlinkService.class),
            new RoleAccess());
    return new FlinkUiProxyController(
        new ClusterProperties(
            "", "", "", "", "", "", "", "", "http://flink-jobmanager", "http://flink-history"),
        access) {
      @Override
      HttpURLConnection open(URL target) {
        capturedTarget = target.toString();
        return upstream;
      }
    };
  }

  private String capturedTarget;

  @Test
  void embeddedHtmlHidesFlinkOwnSiderButPlainRequestDoesNot() throws Exception {
    String html = "<html><head></head><body>flink</body></html>";

    HttpURLConnection embedded = mock(HttpURLConnection.class);
    Mockito.when(embedded.getResponseCode()).thenReturn(200);
    Mockito.when(embedded.getHeaderField("Content-Type")).thenReturn("text/html");
    Mockito.when(embedded.getInputStream())
        .thenReturn(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/flink-ui/history/");
    request.setQueryString("embedded");
    MockHttpServletResponse response = new MockHttpServletResponse();
    controllerWith(embedded).proxy(request, response, administrator());
    assertThat(response.getContentAsString()).contains(".ant-layout-sider{display:none");

    HttpURLConnection plain = mock(HttpURLConnection.class);
    Mockito.when(plain.getResponseCode()).thenReturn(200);
    Mockito.when(plain.getHeaderField("Content-Type")).thenReturn("text/html");
    Mockito.when(plain.getInputStream())
        .thenReturn(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    MockHttpServletRequest plainRequest = new MockHttpServletRequest("GET", "/flink-ui/history/");
    MockHttpServletResponse plainResponse = new MockHttpServletResponse();
    controllerWith(plain).proxy(plainRequest, plainResponse, administrator());
    assertThat(plainResponse.getContentAsString()).doesNotContain("ant-layout-sider");
  }

  @Test
  void administratorCanOpenHistoryAndJobManagerUpstreams() throws Exception {
    assertThat(request("/flink-ui/history/", administrator()).getStatus()).isEqualTo(200);
    assertThat(capturedTarget).isEqualTo("http://flink-history/");

    assertThat(request("/flink-ui/jobmanager/", administrator()).getStatus()).isEqualTo(200);
    assertThat(capturedTarget).isEqualTo("http://flink-jobmanager/");

    // A bare /flink-ui/ defaults to the History dashboard.
    request("/flink-ui/", administrator());
    assertThat(capturedTarget).isEqualTo("http://flink-history/");
  }

  @Test
  void userIsForbidden() throws Exception {
    assertThat(request("/flink-ui/history/", user()).getStatus()).isEqualTo(403);
    assertThat(request("/flink-ui/jobmanager/", user()).getStatus()).isEqualTo(403);
  }

  private MockHttpServletResponse request(String uri, UsernamePasswordAuthenticationToken who)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    controller().proxy(request, response, who);
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
