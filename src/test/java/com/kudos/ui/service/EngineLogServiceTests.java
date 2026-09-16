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

import com.kudos.ui.config.ClusterProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EngineLogServiceTests {

  private static final String ROOT = "/s3v/enginelogs";

  @Test
  void concatenatesPartsInNumericOrderNotAlphabetical() throws Exception {
    // part-10 must follow part-9; sorted as text it would land between 1 and 2.
    EngineLogService logs =
        service(
            Map.of(
                ROOT + "/analyst", List.of(directory(ROOT + "/analyst/3")),
                ROOT + "/analyst/3",
                    List.of(
                        file(ROOT + "/analyst/3/part-10.log"),
                        file(ROOT + "/analyst/3/part-2.log"),
                        file(ROOT + "/analyst/3/part-9.log"))),
            Map.of(
                ROOT + "/analyst/3/part-2.log", "Logging events to ofs://x/spark/eventlogs/app-1\n",
                ROOT + "/analyst/3/part-9.log", "nine\n",
                ROOT + "/analyst/3/part-10.log", "ten\n"));

    assertThat(logs.log("app-1"))
        .isEqualTo("Logging events to ofs://x/spark/eventlogs/app-1\nnine\nten\n");
  }

  @Test
  void ignoresAnotherLaunchThatDoesNotCarryTheApplicationId() throws Exception {
    EngineLogService logs =
        service(
            Map.of(
                ROOT + "/analyst",
                    List.of(directory(ROOT + "/analyst/1"), directory(ROOT + "/analyst/2")),
                ROOT + "/analyst/1", List.of(file(ROOT + "/analyst/1/part-0.log")),
                ROOT + "/analyst/2", List.of(file(ROOT + "/analyst/2/part-0.log"))),
            Map.of(
                ROOT + "/analyst/1/part-0.log", "eventlogs/app-other\nnoise\n",
                ROOT + "/analyst/2/part-0.log", "eventlogs/app-1\nwanted\n"));

    assertThat(logs.log("app-1")).isEqualTo("eventlogs/app-1\nwanted\n");
  }

  @Test
  void returnsEmptyRatherThanFailingWhenNothingWasCollected() throws Exception {
    EngineLogService logs = service(Map.of(), Map.of());

    assertThat(logs.log("app-1")).isEmpty();
  }

  @Test
  void blankConfigurationDisablesTheTab() {
    ClusterProperties properties = properties("");

    EngineLogService logs =
        new EngineLogService(properties, ozone(properties, Map.of(), Map.of()), history(properties));

    assertThat(logs.enabled()).isFalse();
  }

  // ------------------------------------------------------------------ stubs

  private static EngineLogService service(
      Map<String, List<FileEntry>> listings, Map<String, String> contents) {
    ClusterProperties properties = properties(ROOT);
    return new EngineLogService(
        properties, ozone(properties, listings, contents), history(properties));
  }

  private static OzoneService ozone(
      ClusterProperties properties, Map<String, List<FileEntry>> listings, Map<String, String> contents) {
    return new OzoneService(properties, null) {
      @Override
      public List<FileEntry> listEntries(String path) {
        List<FileEntry> entries = listings.get(path);
        if (entries == null) {
          // Ozone raises on a missing path; the service must treat that as "no logs".
          throw new IllegalStateException("No such path: " + path);
        }
        return entries;
      }

      @Override
      public String preview(String path, int maxBytes) {
        return contents.getOrDefault(path, "");
      }
    };
  }

  private static SparkHistoryService history(ClusterProperties properties) {
    return new SparkHistoryService(properties) {
      @Override
      public Optional<SparkApplication> application(String id) {
        return Optional.of(new SparkApplication(id, id, "analyst", "", "", 0, true, "3.5.0"));
      }
    };
  }

  private static ClusterProperties properties(String engineLogsPath) {
    return new ClusterProperties(
        "", "", "", "", "", "", "", "", "", "", "", "", "", engineLogsPath);
  }

  private static FileEntry directory(String path) {
    return new FileEntry(name(path), path, true, 0, "analyst", "analyst", "rwxr-xr-x", 0);
  }

  private static FileEntry file(String path) {
    return new FileEntry(name(path), path, false, 1, "analyst", "analyst", "rw-r--r--", 0);
  }

  private static String name(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
