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

package com.k8spark.ui.api;

import com.k8spark.ui.service.FileEntry;
import com.k8spark.ui.service.HbaseRow;
import com.k8spark.ui.service.HbaseService;
import com.k8spark.ui.service.HdfsService;
import com.k8spark.ui.service.KyuubiService;
import com.k8spark.ui.service.OzoneService;
import com.k8spark.ui.service.QueryResult;
import com.k8spark.ui.service.SparkApplication;
import com.k8spark.ui.service.SparkHistoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ClusterController {

  /** Caps a preview so a stray click on a large file cannot flood the browser. */
  private static final int PREVIEW_BYTES = 64 * 1024;

  /** Caps a result set so the editor grid stays responsive. */
  private static final int MAX_RESULT_ROWS = 1000;

  private final HdfsService hdfs;
  private final KyuubiService kyuubi;
  private final HbaseService hbase;
  private final OzoneService ozone;
  private final SparkHistoryService sparkHistory;

  public ClusterController(
      HdfsService hdfs,
      KyuubiService kyuubi,
      HbaseService hbase,
      OzoneService ozone,
      SparkHistoryService sparkHistory) {
    this.hdfs = hdfs;
    this.kyuubi = kyuubi;
    this.hbase = hbase;
    this.ozone = ozone;
    this.sparkHistory = sparkHistory;
  }

  @GetMapping("/spark/applications")
  List<SparkApplication> sparkApplications(
      @RequestParam(defaultValue = "500") int limit,
      @RequestParam(required = false) String minDate)
      throws Exception {
    return sparkHistory.applications(limit, minDate);
  }

  @GetMapping("/hdfs")
  String hdfs(@RequestParam(defaultValue = "/") String path) throws Exception {
    return hdfs.list(path);
  }

  @GetMapping("/hdfs/list")
  List<FileEntry> hdfsList(@RequestParam(defaultValue = "/") String path) throws Exception {
    return hdfs.listEntries(path);
  }

  @GetMapping("/hdfs/preview")
  String hdfsPreview(@RequestParam String path) throws Exception {
    return hdfs.preview(path, PREVIEW_BYTES);
  }

  @PostMapping("/sql")
  List<Map<String, Object>> sql(@Valid @RequestBody SqlRequest request) throws Exception {
    return kyuubi.query(request.sql());
  }

  @PostMapping("/sql/execute")
  QueryResult sqlExecute(@Valid @RequestBody SqlRequest request) throws Exception {
    return kyuubi.execute(request.sql(), MAX_RESULT_ROWS);
  }

  @GetMapping("/hbase/tables")
  List<String> tables() throws Exception {
    return hbase.tables();
  }

  @GetMapping("/hbase/scan")
  List<HbaseRow> scan(
      @RequestParam String table, @RequestParam(defaultValue = "50") int limit) throws Exception {
    return hbase.scan(table, limit);
  }

  @GetMapping("/ozone")
  List<String> ozone(@RequestParam(defaultValue = "/") String path) throws Exception {
    return ozone.list(path);
  }

  @GetMapping("/ozone/list")
  List<FileEntry> ozoneList(@RequestParam(defaultValue = "/") String path) throws Exception {
    return ozone.listEntries(path);
  }

  @GetMapping("/ozone/preview")
  String ozonePreview(@RequestParam String path) throws Exception {
    return ozone.preview(path, PREVIEW_BYTES);
  }

  record SqlRequest(@NotBlank String sql) {}
}
