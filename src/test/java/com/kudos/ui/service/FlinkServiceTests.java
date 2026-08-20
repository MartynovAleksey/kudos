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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kudos.ui.config.ClusterProperties;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlinkServiceTests {

  /** A FlinkService whose one HTTP call returns {@code overviewJson}. */
  private FlinkService service(String jobmanagerUrl, String historyUrl, String overviewJson)
      throws Exception {
    HttpURLConnection upstream = mock(HttpURLConnection.class);
    when(upstream.getResponseCode()).thenReturn(200);
    when(upstream.getInputStream())
        .thenReturn(new ByteArrayInputStream(overviewJson.getBytes(StandardCharsets.UTF_8)));
    ClusterProperties properties =
        new ClusterProperties(
            "", "", "", "", "", "", "", "", "", "", jobmanagerUrl, historyUrl, "");
    return new FlinkService(properties) {
      @Override
      HttpURLConnection open(URL target) {
        return upstream;
      }
    };
  }

  private static final String OVERVIEW =
      "{\"jobs\":["
          + "{\"jid\":\"abc\",\"name\":\"wordcount\",\"state\":\"RUNNING\",\"start-time\":1000,\"end-time\":-1,\"duration\":5000},"
          + "{\"jid\":\"def\",\"name\":\"done\",\"state\":\"FINISHED\",\"start-time\":2000,\"end-time\":2500,\"duration\":500}"
          + "]}";

  @Test
  void runningApplicationsKeepOnlyLiveJobs() throws Exception {
    List<SparkApplication> apps = service("http://jm", "http://hs", OVERVIEW).runningApplications();

    assertThat(apps).hasSize(1);
    SparkApplication running = apps.getFirst();
    assertThat(running.id()).isEqualTo("flink-abc");
    assertThat(running.name()).isEqualTo("wordcount");
    assertThat(running.completed()).isFalse();
    assertThat(running.durationMillis()).isEqualTo(5000);
    assertThat(running.sparkVersion()).isEqualTo("Flink job");
  }

  @Test
  void completedApplicationsKeepOnlyTerminalJobsWithEndTime() throws Exception {
    List<SparkApplication> apps = service("http://jm", "http://hs", OVERVIEW).completedApplications();

    assertThat(apps).hasSize(1);
    SparkApplication done = apps.getFirst();
    assertThat(done.id()).isEqualTo("flink-def");
    assertThat(done.completed()).isTrue();
    assertThat(done.endTime()).isNotBlank();
  }

  @Test
  void blankUrlYieldsNoApplications() throws Exception {
    assertThat(service("", "", "{}").runningApplications()).isEmpty();
    assertThat(service("", "", "{}").completedApplications()).isEmpty();
  }

  @Test
  void unreachableEndpointDoesNotBreakTheJobsScreen() throws Exception {
    HttpURLConnection failing = mock(HttpURLConnection.class);
    when(failing.getResponseCode()).thenReturn(503);
    ClusterProperties properties =
        new ClusterProperties("", "", "", "", "", "", "", "", "", "", "http://jm", "http://hs", "");
    FlinkService service =
        new FlinkService(properties) {
          @Override
          HttpURLConnection open(URL target) {
            return failing;
          }
        };

    assertThat(service.runningApplications()).isEmpty();
    assertThat(service.completedApplications()).isEmpty();
  }
}
