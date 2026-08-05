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

package com.kudos.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HbaseServiceRestTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void decodesCellsAndAlwaysClosesTheRestScanner() throws Exception {
    HbaseRestClient rest = mock(HbaseRestClient.class);
    String location = "http://hbase.test.local:8080/demo/scanner/1";
    when(rest.post(eq("/demo/scanner"), any()))
        .thenReturn(new HbaseRestClient.Response(201, Map.of("Location", List.of(location)), NullNode.getInstance()));
    when(rest.scannerPath(location)).thenReturn("/demo/scanner/1");
    when(rest.get("/demo/scanner/1?n=3&c=" + Integer.MAX_VALUE))
        .thenReturn(
            new HbaseRestClient.Response(
                200,
                Map.of(),
                MAPPER.readTree(
                    """
                    {"Row":[{"key":"AHIvMQ==","Cell":[
                      {"column":"Y2Y6YQ==","timestamp":12,"$":"aGVsbG8="},
                      {"column":"Y2Y6YQ==","timestamp":11,"$":"b2xkZXI="},
                      {"column":"Y2Y6Yg==","timestamp":11,"$":"AAE="}
                    ]}]}""")));

    List<HbaseRow> rows = service(rest).scan("demo", "", true, null, 2, null, null);

    assertEquals(1, rows.size());
    assertEquals("\\x00r/1", rows.getFirst().rowKey());
    assertEquals(new HbaseCell("cf:a", "hello", 12, false), rows.getFirst().cells().getFirst());
    assertEquals(new HbaseCell("cf:a", "older", 11, false), rows.getFirst().cells().get(1));
    assertEquals(new HbaseCell("cf:b", "AAE=", 11, true), rows.getFirst().cells().get(2));
    verify(rest).delete("/demo/scanner/1");
  }

  @Test
  void keepsTheExistingFilterAndColumnContractOnTheStandardRestScanEndpoint() throws Exception {
    HbaseRestClient rest = mock(HbaseRestClient.class);
    when(rest.get(any()))
        .thenReturn(new HbaseRestClient.Response(200, Map.of(), MAPPER.readTree("{\"Row\":[]}")));

    service(rest)
        .scan(
            "demo",
            "r 1",
            false,
            "pre",
            3,
            List.of("cf:a", "other"),
            "ValueFilter(=,'binary:ok')");

    verify(rest)
        .get(
            "/demo/pre*?limit=4&startrow=r%201&column=cf%3Aa&column=other"
                + "&filter=ValueFilter%28%3D%2C%27binary%3Aok%27%29");
    verify(rest, never()).post(eq("/demo/scanner"), any());
  }

  @Test
  void listsTablesThroughTheStandardRestEndpoint() throws Exception {
    HbaseRestClient rest = mock(HbaseRestClient.class);
    when(rest.get("/"))
        .thenReturn(
            new HbaseRestClient.Response(
                200, Map.of(), MAPPER.readTree("{\"table\":[{\"name\":\"demo\"}]}")));

    assertEquals(List.of(new HbaseTableInfo("demo")), service(rest).tables());

    verify(rest).get("/");
  }

  private static HbaseService service(HbaseRestClient rest) {
    return new HbaseService(rest);
  }
}
