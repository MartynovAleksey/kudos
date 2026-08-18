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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** HBase browser operations backed exclusively by the Kerberos-protected REST Gateway. */
@Service
public class HbaseService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int BULK_BATCH = 2000;

  private final HbaseRestClient rest;

  public HbaseService(HbaseRestClient rest) {
    this.rest = rest;
  }

  // ---------------------------------------------------------------- tables

  public List<HbaseTableInfo> tables() throws Exception {
    JsonNode response = rest.get("/").body();
    JsonNode tableList = response.isArray() ? response : array(response, "table");
    List<HbaseTableInfo> tables = new ArrayList<>();
    for (JsonNode table : tableList) {
      tables.add(new HbaseTableInfo(table.path("name").asText()));
    }
    return tables;
  }

  public List<HbaseColumnFamily> describe(String table) throws Exception {
    JsonNode schema = rest.get(tablePath(table) + "/schema").body();
    List<HbaseColumnFamily> families = new ArrayList<>();
    for (JsonNode family : array(schema, "ColumnSchema")) {
      families.add(toColumnFamily(family));
    }
    return families;
  }

  public List<HbaseRegion> regions(String table) throws Exception {
    JsonNode response = rest.get(tablePath(table) + "/regions").body();
    List<HbaseRegion> regions = new ArrayList<>();
    for (JsonNode region : array(response, "Region")) {
      regions.add(
          new HbaseRegion(
              region.path("name").asText(),
              binaryKey(region.path("startKey").asText()),
              binaryKey(region.path("endKey").asText())));
    }
    return regions;
  }

  public void createTable(String table, List<HbaseColumnFamily> families) throws Exception {
    if (families == null || families.isEmpty()) {
      throw new IllegalArgumentException("A table needs at least one column family");
    }
    ObjectNode schema = MAPPER.createObjectNode().put("name", table);
    ArrayNode columns = schema.putArray("ColumnSchema");
    for (HbaseColumnFamily family : families) {
      columns.add(toSchema(family));
    }
    rest.put(tablePath(table) + "/schema", schema);
  }

  public void deleteTable(String table) throws Exception {
    rest.delete(tablePath(table) + "/schema");
  }

  public void addColumnFamily(String table, HbaseColumnFamily family) throws Exception {
    rest.post(tablePath(table) + "/schema", familySchema(table, family));
  }

  public void modifyColumnFamily(String table, HbaseColumnFamily family) throws Exception {
    rest.post(tablePath(table) + "/schema", familySchema(table, family));
  }

  // ------------------------------------------------------------------ rows

  public List<HbaseRow> scan(
      String table,
      String startRow,
      boolean startInclusive,
      String prefix,
      int limit,
      List<String> columns,
      String filter)
      throws Exception {
    if (limit <= 0) {
      throw new IllegalArgumentException("Scan limit must be positive");
    }
    List<HbaseRow> rows =
        filter == null || filter.isBlank()
            ? scanWithScanner(table, startRow, prefix, limit, columns)
            : scanWithFilter(table, startRow, prefix, limit, columns, filter);
    if (!startInclusive && startRow != null && !startRow.isEmpty()) {
      rows.removeIf(row -> row.rowKey().equals(startRow));
    }
    return rows.size() <= limit ? rows : rows.subList(0, limit);
  }

  /** Row keys starting with {@code prefix}, for the search box autocomplete. */
  public List<String> autocompleteRows(String table, String prefix, int limit) throws Exception {
    List<String> keys = new ArrayList<>();
    for (HbaseRow row : scan(table, "", true, prefix, limit, null, null)) {
      keys.add(row.rowKey());
    }
    return keys;
  }

  /** The latest value of every selected cell in a single row. */
  public HbaseRow row(String table, String rowKey, List<String> columns) throws Exception {
    try {
      return firstRow(rest.get(rowPath(table, rowKey, columns)).body());
    } catch (HbaseRestClient.HbaseRestException error) {
      if (error.status() == 404) {
        return null;
      }
      throw error;
    }
  }

  /** The version history of one cell, newest first. */
  public List<HbaseCell> cellVersions(String table, String rowKey, String column, int maxVersions)
      throws Exception {
    HbaseRow row = firstRow(rest.get(rowPath(table, rowKey, List.of(column)) + "?v=" + maxVersions).body());
    return row == null ? List.of() : row.cells();
  }

  // ------------------------------------------------------------- mutations

  public void putRow(String table, String rowKey, Map<String, String> cells) throws Exception {
    if (cells == null || cells.isEmpty()) {
      throw new IllegalArgumentException("No cells to write");
    }
    ObjectNode row = MAPPER.createObjectNode();
    row.put("key", base64(rowKey));
    ArrayNode values = row.putArray("Cell");
    cells.forEach(
        (column, value) -> values.add(cell(column, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8))));
    putRows(table, List.of(row), rowKey);
  }

  /** Writes raw bytes to a single cell, for the binary upload path. */
  public void putCellBytes(String table, String rowKey, String column, byte[] value)
      throws Exception {
    ObjectNode row = MAPPER.createObjectNode();
    row.put("key", base64(rowKey));
    row.putArray("Cell").add(cell(column, value));
    putRows(table, List.of(row), rowKey);
  }

  public void deleteCells(String table, String rowKey, List<String> columns) throws Exception {
    rest.delete(rowPath(table, rowKey, columns));
  }

  public void deleteRow(String table, String rowKey) throws Exception {
    rest.delete(rowPath(table, rowKey, null));
  }

  /** Loads the RFC-4180 CSV accepted by the browser's bulk-import form. */
  public int bulkUpload(String table, byte[] csv) throws Exception {
    List<String[]> lines = parseCsv(new String(csv, StandardCharsets.UTF_8));
    if (lines.size() < 2) {
      throw new IllegalArgumentException("The CSV needs a header row and at least one data row");
    }
    String[] header = lines.get(0);
    List<ObjectNode> batch = new ArrayList<>();
    int written = 0;
    for (int i = 1; i < lines.size(); i++) {
      String[] fields = lines.get(i);
      if (fields.length == 0 || fields[0].isEmpty()) {
        continue;
      }
      ObjectNode row = MAPPER.createObjectNode().put("key", base64(fields[0]));
      ArrayNode values = row.putArray("Cell");
      for (int c = 1; c < fields.length && c < header.length; c++) {
        if (!fields[c].isEmpty()) {
          values.add(cell(header[c], fields[c].getBytes(StandardCharsets.UTF_8)));
        }
      }
      if (!values.isEmpty()) {
        batch.add(row);
      }
      if (batch.size() >= BULK_BATCH) {
        putRows(table, batch, fields[0]);
        written += batch.size();
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      putRows(table, batch, null);
      written += batch.size();
    }
    return written;
  }

  // -------------------------------------------------------------- internals

  private List<HbaseRow> scanWithScanner(
      String table, String startRow, String prefix, int limit, List<String> columns) throws Exception {
    ObjectNode scanner = MAPPER.createObjectNode().put("caching", limit + 1);
    if (startRow != null && !startRow.isEmpty()) {
      scanner.put("startRow", base64(startRow));
    }
    addColumns(scanner, columns);
    if (prefix != null && !prefix.isEmpty()) {
      ObjectNode filter = MAPPER.createObjectNode();
      filter.put("type", "PrefixFilter");
      filter.put("value", base64(prefix));
      scanner.put("filter", MAPPER.writeValueAsString(filter));
    }
    HbaseRestClient.Response created = rest.post(tablePath(table) + "/scanner", scanner);
    String location = created.header("Location");
    if (location == null || location.isBlank()) {
      throw new IllegalStateException("HBase REST did not return a scanner Location");
    }
    String scannerPath = rest.scannerPath(location);
    try {
      return rows(rest.get(scannerPath + "?n=" + (limit + 1) + "&c=" + Integer.MAX_VALUE).body());
    } finally {
      deleteScanner(scannerPath);
    }
  }

  /** The legacy REST scan endpoint is the HBase-supported transport for ParseFilter strings. */
  private List<HbaseRow> scanWithFilter(
      String table, String startRow, String prefix, int limit, List<String> columns, String filter)
      throws Exception {
    StringBuilder path = new StringBuilder(tablePath(table)).append('/');
    if (prefix != null && !prefix.isEmpty()) {
      path.append(HbaseRestClient.pathSegment(prefix));
    }
    path.append('*').append("?limit=").append(limit + 1);
    if (startRow != null && !startRow.isEmpty()) {
      path.append("&startrow=").append(HbaseRestClient.pathSegment(startRow));
    }
    for (String column : nonBlank(columns)) {
      path.append("&column=").append(HbaseRestClient.pathSegment(column));
    }
    path.append("&filter=").append(HbaseRestClient.pathSegment(filter.trim()));
    return rows(rest.get(path.toString()).body());
  }

  private void deleteScanner(String scannerPath) throws Exception {
    try {
      rest.delete(scannerPath);
    } catch (HbaseRestClient.HbaseRestException error) {
      if (error.status() != 404 && error.status() != 410) {
        throw error;
      }
    }
  }

  private void putRows(String table, List<ObjectNode> rows, String fallbackRow) throws Exception {
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode payloadRows = body.putArray("Row");
    rows.forEach(payloadRows::add);
    String anchor = fallbackRow == null ? "_kudos_bulk" : fallbackRow;
    rest.put(rowPath(table, anchor, null), body);
  }

  private static ObjectNode familySchema(String table, HbaseColumnFamily family) {
    ObjectNode schema = MAPPER.createObjectNode().put("name", table);
    schema.putArray("ColumnSchema").add(toSchema(family));
    return schema;
  }

  private static ObjectNode toSchema(HbaseColumnFamily family) {
    ObjectNode schema = MAPPER.createObjectNode().put("name", family.name());
    put(schema, "VERSIONS", family.maxVersions());
    put(schema, "MIN_VERSIONS", family.minVersions());
    put(schema, "COMPRESSION", family.compression());
    put(schema, "TTL", family.timeToLive());
    put(schema, "BLOCKCACHE", family.blockCacheEnabled());
    put(schema, "BLOOMFILTER", family.bloomFilterType());
    put(schema, "DATA_BLOCK_ENCODING", family.dataBlockEncoding());
    put(schema, "IN_MEMORY", family.inMemory());
    return schema;
  }

  private static void put(ObjectNode target, String name, Object value) {
    if (value != null && (!(value instanceof String text) || !text.isBlank())) {
      target.put(name, String.valueOf(value));
    }
  }

  private static HbaseColumnFamily toColumnFamily(JsonNode family) {
    return new HbaseColumnFamily(
        family.path("name").asText(),
        integer(family, "VERSIONS"),
        integer(family, "MIN_VERSIONS"),
        text(family, "COMPRESSION"),
        integer(family, "TTL"),
        bool(family, "BLOCKCACHE"),
        text(family, "BLOOMFILTER"),
        text(family, "DATA_BLOCK_ENCODING"),
        bool(family, "IN_MEMORY"));
  }

  private static Integer integer(JsonNode node, String name) {
    String value = text(node, name);
    return value == null || value.isBlank() ? null : Integer.valueOf(value);
  }

  private static Boolean bool(JsonNode node, String name) {
    String value = text(node, name);
    return value == null || value.isBlank() ? null : Boolean.valueOf(value);
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static void addColumns(ObjectNode scanner, List<String> columns) {
    List<String> selected = nonBlank(columns);
    if (selected.isEmpty()) {
      return;
    }
    ArrayNode values = scanner.putArray("column");
    for (String column : selected) {
      values.add(base64(column));
    }
  }

  private static ObjectNode cell(String column, byte[] value) {
    ObjectNode cell = MAPPER.createObjectNode();
    cell.put("column", base64(column));
    cell.put("$", Base64.getEncoder().encodeToString(value));
    return cell;
  }

  private static List<HbaseRow> rows(JsonNode body) {
    List<HbaseRow> rows = new ArrayList<>();
    for (JsonNode row : array(body, "Row")) {
      rows.add(toRow(row));
    }
    return rows;
  }

  private static HbaseRow firstRow(JsonNode body) {
    List<HbaseRow> rows = rows(body);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static HbaseRow toRow(JsonNode row) {
    List<HbaseCell> cells = new ArrayList<>();
    for (JsonNode cell : array(row, "Cell")) {
      String column = new String(Base64.getDecoder().decode(cell.path("column").asText()), StandardCharsets.UTF_8);
      byte[] value = Base64.getDecoder().decode(cell.path("$").asText());
      Decoded decoded = decode(value);
      cells.add(new HbaseCell(column, decoded.text(), cell.path("timestamp").asLong(), decoded.binary()));
    }
    return new HbaseRow(binaryKey(row.path("key").asText()), cells);
  }

  private static ArrayNode array(JsonNode node, String name) {
    JsonNode value = node.path(name);
    return value.isArray() ? (ArrayNode) value : MAPPER.createArrayNode();
  }

  private static String tablePath(String table) {
    return "/" + HbaseRestClient.pathSegment(table);
  }

  private static String rowPath(String table, String row, List<String> columns) {
    StringBuilder path = new StringBuilder(tablePath(table)).append('/').append(HbaseRestClient.pathSegment(row));
    List<String> selected = nonBlank(columns);
    if (!selected.isEmpty()) {
      path.append('/');
      for (int i = 0; i < selected.size(); i++) {
        if (i > 0) {
          path.append(',');
        }
        path.append(HbaseRestClient.pathSegment(selected.get(i)));
      }
    }
    return path.toString();
  }

  private static List<String> nonBlank(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().filter(value -> value != null && !value.isBlank()).toList();
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String binaryKey(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    byte[] bytes = Base64.getDecoder().decode(value);
    StringBuilder text = new StringBuilder();
    for (byte valueByte : bytes) {
      int unsigned = Byte.toUnsignedInt(valueByte);
      if (unsigned >= 0x20 && unsigned <= 0x7e && unsigned != '\\') {
        text.append((char) unsigned);
      } else {
        text.append("\\x%02X".formatted(unsigned));
      }
    }
    return text.toString();
  }

  /** UTF-8 text where the bytes are printable, Base64 otherwise. */
  private static Decoded decode(byte[] bytes) {
    try {
      CharsetDecoder decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
      for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i);
        if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
          return new Decoded(Base64.getEncoder().encodeToString(bytes), true);
        }
      }
      return new Decoded(text, false);
    } catch (CharacterCodingException notText) {
      return new Decoded(Base64.getEncoder().encodeToString(bytes), true);
    }
  }

  private record Decoded(String text, boolean binary) {}

  /** Minimal RFC-4180 CSV: comma-separated, double-quoted fields, double-quote escapes. */
  private static List<String[]> parseCsv(String text) {
    List<String[]> rows = new ArrayList<>();
    List<String> field = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          current.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == ',') {
        field.add(current.toString());
        current.setLength(0);
      } else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        field.add(current.toString());
        current.setLength(0);
        rows.add(field.toArray(new String[0]));
        field = new ArrayList<>();
      } else {
        current.append(ch);
      }
    }
    if (current.length() > 0 || !field.isEmpty()) {
      field.add(current.toString());
      rows.add(field.toArray(new String[0]));
    }
    return rows;
  }
}
