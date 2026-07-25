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

import com.k8spark.ui.service.ExcelResults;
import com.k8spark.ui.service.FileEntry;
import com.k8spark.ui.service.HbaseCell;
import com.k8spark.ui.service.HbaseColumnFamily;
import com.k8spark.ui.service.HbaseRegion;
import com.k8spark.ui.service.HbaseRow;
import com.k8spark.ui.service.HbaseService;
import com.k8spark.ui.service.HbaseTableInfo;
import com.k8spark.ui.service.HdfsService;
import com.k8spark.ui.service.KyuubiService;
import com.k8spark.ui.service.OzoneService;
import com.k8spark.ui.service.QueryResult;
import com.k8spark.ui.service.SparkApplication;
import com.k8spark.ui.service.SparkHistoryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ClusterController {

  /** Caps a preview so a stray click on a large file cannot flood the browser. */
  private static final int PREVIEW_BYTES = 64 * 1024;

  /** Caps a result set so the editor grid stays responsive. */
  private static final int MAX_RESULT_ROWS = 1000;

  /** A larger cap for the Excel export, which does not render in the browser. */
  private static final int EXPORT_MAX_ROWS = 100_000;

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

  @PostMapping("/sql/export")
  void sqlExport(@Valid @RequestBody SqlRequest request, HttpServletResponse response)
      throws Exception {
    QueryResult result = kyuubi.execute(request.sql(), EXPORT_MAX_ROWS);
    response.setContentType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename=\"query-results.xlsx\"");
    ExcelResults.write(result, response.getOutputStream());
  }

  @GetMapping("/hbase/tables")
  List<HbaseTableInfo> tables() throws Exception {
    return hbase.tables();
  }

  @GetMapping("/hbase/describe")
  List<HbaseColumnFamily> describe(@RequestParam String table) throws Exception {
    return hbase.describe(table);
  }

  @GetMapping("/hbase/regions")
  List<HbaseRegion> regions(@RequestParam String table) throws Exception {
    return hbase.regions(table);
  }

  @PostMapping("/hbase/table/create")
  void createTable(@Valid @RequestBody CreateTableRequest request) throws Exception {
    hbase.createTable(request.table(), request.families());
  }

  @PostMapping("/hbase/table/enable")
  void enableTable(@Valid @RequestBody TableRequest request) throws Exception {
    hbase.enableTable(request.table());
  }

  @PostMapping("/hbase/table/disable")
  void disableTable(@Valid @RequestBody TableRequest request) throws Exception {
    hbase.disableTable(request.table());
  }

  @PostMapping("/hbase/table/truncate")
  void truncateTable(@Valid @RequestBody TruncateRequest request) throws Exception {
    hbase.truncateTable(request.table(), request.preserveSplits());
  }

  @PostMapping("/hbase/table/delete")
  void deleteTable(@Valid @RequestBody TableRequest request) throws Exception {
    hbase.deleteTable(request.table());
  }

  @PostMapping("/hbase/family/add")
  void addFamily(@Valid @RequestBody FamilyRequest request) throws Exception {
    hbase.addColumnFamily(request.table(), request.family());
  }

  @PostMapping("/hbase/family/modify")
  void modifyFamily(@Valid @RequestBody FamilyRequest request) throws Exception {
    hbase.modifyColumnFamily(request.table(), request.family());
  }

  @PostMapping("/hbase/family/delete")
  void deleteFamily(@Valid @RequestBody FamilyDeleteRequest request) throws Exception {
    hbase.deleteColumnFamily(request.table(), request.family());
  }

  @GetMapping("/hbase/scan")
  List<HbaseRow> scan(
      @RequestParam String table,
      @RequestParam(defaultValue = "") String start,
      @RequestParam(defaultValue = "true") boolean startInclusive,
      @RequestParam(required = false) String prefix,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) List<String> columns,
      @RequestParam(required = false) String filter)
      throws Exception {
    return hbase.scan(
        table, start, startInclusive, prefix, Math.min(limit, MAX_RESULT_ROWS), columns, filter);
  }

  @GetMapping("/hbase/autocomplete")
  List<String> autocomplete(
      @RequestParam String table,
      @RequestParam(defaultValue = "") String prefix,
      @RequestParam(defaultValue = "20") int limit)
      throws Exception {
    return hbase.autocompleteRows(table, prefix, limit);
  }

  @GetMapping("/hbase/row")
  HbaseRow row(
      @RequestParam String table,
      @RequestParam String row,
      @RequestParam(required = false) List<String> columns)
      throws Exception {
    return hbase.row(table, row, columns);
  }

  @GetMapping("/hbase/cell/versions")
  List<HbaseCell> cellVersions(
      @RequestParam String table,
      @RequestParam String row,
      @RequestParam String column,
      @RequestParam(defaultValue = "10") int versions)
      throws Exception {
    return hbase.cellVersions(table, row, column, versions);
  }

  @PostMapping("/hbase/row")
  void putRow(@Valid @RequestBody PutRowRequest request) throws Exception {
    hbase.putRow(request.table(), request.row(), request.cells());
  }

  @PostMapping("/hbase/row/delete")
  void deleteRow(@Valid @RequestBody RowDeleteRequest request) throws Exception {
    hbase.deleteRow(request.table(), request.row());
  }

  @PostMapping("/hbase/cell/delete")
  void deleteCells(@Valid @RequestBody CellDeleteRequest request) throws Exception {
    hbase.deleteCells(request.table(), request.row(), request.columns());
  }

  @PostMapping("/hbase/cell/upload")
  void uploadCell(
      @RequestParam String table,
      @RequestParam String row,
      @RequestParam String column,
      @RequestPart MultipartFile file)
      throws Exception {
    hbase.putCellBytes(table, row, column, file.getBytes());
  }

  @PostMapping("/hbase/bulk")
  int bulkUpload(@RequestParam String table, @RequestPart MultipartFile file) throws Exception {
    return hbase.bulkUpload(table, file.getBytes());
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

  record CreateTableRequest(
      @NotBlank String table, @NotEmpty List<HbaseColumnFamily> families) {}

  record TableRequest(@NotBlank String table) {}

  record TruncateRequest(@NotBlank String table, boolean preserveSplits) {}

  record FamilyRequest(@NotBlank String table, @NotNull HbaseColumnFamily family) {}

  record FamilyDeleteRequest(@NotBlank String table, @NotBlank String family) {}

  record PutRowRequest(
      @NotBlank String table, @NotBlank String row, @NotEmpty Map<String, String> cells) {}

  record RowDeleteRequest(@NotBlank String table, @NotBlank String row) {}

  record CellDeleteRequest(
      @NotBlank String table, @NotBlank String row, @NotEmpty List<String> columns) {}
}
