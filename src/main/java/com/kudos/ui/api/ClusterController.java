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

import com.kudos.ui.service.ExcelResults;
import com.kudos.ui.service.FileEntry;
import com.kudos.ui.service.HbaseCell;
import com.kudos.ui.service.HbaseColumnFamily;
import com.kudos.ui.service.HbaseRegion;
import com.kudos.ui.service.HbaseRow;
import com.kudos.ui.service.HbaseService;
import com.kudos.ui.service.HbaseTableInfo;
import com.kudos.ui.service.HdfsService;
import com.kudos.ui.service.KyuubiService;
import com.kudos.ui.service.KyuubiSessionInfo;
import com.kudos.ui.service.KyuubiSessionMonitor;
import com.kudos.ui.service.OzoneService;
import com.kudos.ui.service.QueryResult;
import com.kudos.ui.service.SparkApplication;
import com.kudos.ui.service.SparkApplicationAccessService;
import com.kudos.ui.service.SparkHistoryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping({"/api", "/ui-api"})
public class ClusterController {

  /** Caps a preview so a stray click on a large file cannot flood the browser. */
  private static final int PREVIEW_BYTES = 64 * 1024;

  /** Caps a result set so the editor grid stays responsive. */
  private static final int MAX_RESULT_ROWS = 1000;

  /** A larger cap for direct API exports, which do not render in the browser. */
  private static final int EXPORT_MAX_ROWS = 100_000;

  private final HdfsService hdfs;
  private final KyuubiService kyuubi;
  private final HbaseService hbase;
  private final OzoneService ozone;
  private final SparkHistoryService sparkHistory;
  private final SparkApplicationAccessService sparkAccess;

  public ClusterController(
      HdfsService hdfs,
      KyuubiService kyuubi,
      HbaseService hbase,
      OzoneService ozone,
      SparkHistoryService sparkHistory,
      SparkApplicationAccessService sparkAccess) {
    this.hdfs = hdfs;
    this.kyuubi = kyuubi;
    this.hbase = hbase;
    this.ozone = ozone;
    this.sparkHistory = sparkHistory;
    this.sparkAccess = sparkAccess;
  }

  @GetMapping("/spark/applications")
  List<SparkApplication> sparkApplications(
      @RequestParam(defaultValue = "500") int limit,
      @RequestParam(required = false) String minDate,
      @RequestParam(required = false) String user,
      Authentication authentication)
      throws Exception {
    return sparkAccess.applications(authentication, limit, minDate, user);
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

  @PostMapping("/hdfs/mkdir")
  void hdfsMkdir(@Valid @RequestBody PathRequest request) throws Exception {
    hdfs.mkdirs(request.path());
  }

  @PostMapping("/hdfs/delete")
  void hdfsDelete(@Valid @RequestBody DeleteRequest request) throws Exception {
    hdfs.delete(request.path(), request.recursive());
  }

  @PostMapping("/hdfs/rename")
  void hdfsRename(@Valid @RequestBody RenameRequest request) throws Exception {
    hdfs.rename(request.path(), request.destination());
  }

  @PostMapping("/hdfs/chmod")
  void hdfsChmod(@Valid @RequestBody ChmodRequest request) throws Exception {
    hdfs.setPermission(request.path(), request.permission());
  }

  @PostMapping("/hdfs/chown")
  void hdfsChown(@Valid @RequestBody ChownRequest request) throws Exception {
    hdfs.setOwner(request.path(), request.owner(), request.group());
  }

  @PostMapping("/hdfs/upload")
  void hdfsUpload(@RequestParam String path, @RequestPart MultipartFile file) throws Exception {
    hdfs.upload(join(path, file.getOriginalFilename()), file.getBytes());
  }

  @GetMapping("/hdfs/download")
  void hdfsDownload(@RequestParam String path, HttpServletResponse response) throws Exception {
    response.setContentType("application/octet-stream");
    response.setHeader(
        "Content-Disposition", "attachment; filename=\"" + fileName(path) + "\"");
    hdfs.download(path, response.getOutputStream());
  }

  @PostMapping("/sql")
  List<Map<String, Object>> sql(@Valid @RequestBody SqlRequest request) throws Exception {
    return kyuubi.query(request.sql());
  }

  @PostMapping("/sql/execute")
  QueryResult sqlExecute(@Valid @RequestBody SqlRequest request) throws Exception {
    return kyuubi.execute(request.sql(), MAX_RESULT_ROWS);
  }

  @GetMapping("/sessions")
  List<KyuubiSessionInfo> sessions() {
    return kyuubi.sessions();
  }

  @GetMapping("/sessions/{id}/monitor")
  KyuubiSessionMonitor sessionMonitor(@PathVariable String id) {
    return kyuubi.monitor(id);
  }

  @PostMapping("/sessions/{id}/operations/{operationId}/execute")
  QueryResult executeOperation(@PathVariable String id, @PathVariable String operationId) throws Exception {
    return kyuubi.executeOperation(id, operationId, MAX_RESULT_ROWS);
  }

  @GetMapping("/sessions/{id}/operations/{operationId}/sql")
  void downloadOperationSql(
      @PathVariable String id, @PathVariable String operationId, HttpServletResponse response)
      throws Exception {
    response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
    response.setContentType("application/sql; charset=UTF-8");
    response.setHeader("Content-Disposition", "attachment; filename=\"kyuubi-operation.sql\"");
    response.getWriter().write(kyuubi.operationSql(id, operationId));
  }

  @PostMapping("/sessions/start")
  KyuubiSessionInfo startSession(@Valid @RequestBody SessionStartRequest request) throws Exception {
    return kyuubi.start(request.name(), request.sparkParams());
  }

  @PostMapping("/sessions/stop")
  void stopSession(@Valid @RequestBody SessionRequest request) {
    kyuubi.stop(request.id());
  }

  @PostMapping("/sessions/restart")
  KyuubiSessionInfo restartSession(@Valid @RequestBody SessionRestartRequest request)
      throws Exception {
    return kyuubi.restart(request.id(), request.sparkParams());
  }

  @PostMapping("/sessions/activate")
  void activateSession(@Valid @RequestBody SessionRequest request) {
    kyuubi.activate(request.id());
  }

  @PostMapping("/sql/export")
  void sqlExport(@Valid @RequestBody SqlRequest request, HttpServletResponse response)
      throws Exception {
    writeExcel(kyuubi.execute(request.sql(), EXPORT_MAX_ROWS), response);
  }

  @PostMapping("/sql/export/results")
  void sqlExportResults(@RequestBody QueryResult result, HttpServletResponse response)
      throws Exception {
    writeExcel(result, response);
  }

  private static void writeExcel(QueryResult result, HttpServletResponse response)
      throws Exception {
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

  @PostMapping("/ozone/mkdir")
  void ozoneMkdir(@Valid @RequestBody PathRequest request) throws Exception {
    ozone.mkdirs(request.path());
  }

  @PostMapping("/ozone/delete")
  void ozoneDelete(@Valid @RequestBody DeleteRequest request) throws Exception {
    ozone.delete(request.path(), request.recursive());
  }

  @PostMapping("/ozone/rename")
  void ozoneRename(@Valid @RequestBody RenameRequest request) throws Exception {
    ozone.rename(request.path(), request.destination());
  }

  @PostMapping("/ozone/chmod")
  void ozoneChmod(@Valid @RequestBody ChmodRequest request) throws Exception {
    ozone.setPermission(request.path(), request.permission());
  }

  @PostMapping("/ozone/chown")
  void ozoneChown(@Valid @RequestBody ChownRequest request) throws Exception {
    ozone.setOwner(request.path(), request.owner(), request.group());
  }

  @PostMapping("/ozone/upload")
  void ozoneUpload(@RequestParam String path, @RequestPart MultipartFile file) throws Exception {
    ozone.upload(join(path, file.getOriginalFilename()), file.getBytes());
  }

  @GetMapping("/ozone/download")
  void ozoneDownload(@RequestParam String path, HttpServletResponse response) throws Exception {
    response.setContentType("application/octet-stream");
    response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName(path) + "\"");
    ozone.download(path, response.getOutputStream());
  }

  private static String join(String dir, String name) {
    String base = dir.endsWith("/") ? dir : dir + "/";
    return base + name;
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  record SqlRequest(@NotBlank String sql) {}

  record SessionStartRequest(String name, String sparkParams) {}

  record SessionRequest(@NotBlank String id) {}

  record SessionRestartRequest(@NotBlank String id, String sparkParams) {}

  record PathRequest(@NotBlank String path) {}

  record DeleteRequest(@NotBlank String path, boolean recursive) {}

  record RenameRequest(@NotBlank String path, @NotBlank String destination) {}

  record ChmodRequest(@NotBlank String path, @NotBlank String permission) {}

  record ChownRequest(@NotBlank String path, String owner, String group) {}

  record CreateTableRequest(
      @NotBlank String table, @NotEmpty List<HbaseColumnFamily> families) {}

  record TableRequest(@NotBlank String table) {}

  record FamilyRequest(@NotBlank String table, @NotNull HbaseColumnFamily family) {}

  record PutRowRequest(
      @NotBlank String table, @NotBlank String row, @NotEmpty Map<String, String> cells) {}

  record RowDeleteRequest(@NotBlank String table, @NotBlank String row) {}

  record CellDeleteRequest(
      @NotBlank String table, @NotBlank String row, @NotEmpty List<String> columns) {}
}
