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

package com.k8spark.ui.service;

import com.k8spark.ui.config.ClusterProperties;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.filter.ParseFilter;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.util.Bytes;
import org.springframework.stereotype.Service;

/**
 * The whole of the HBase browser's data path. Every method runs as the
 * signed-in user through {@link KerberosExecutor}, so the cluster applies that
 * user's own authorization. The operations mirror what Hue's HBase app exposes
 * over Thrift, done here against the native client: table lifecycle, column
 * family administration, scanning with filters, cell version history and the
 * row and cell mutations.
 */
@Service
public class HbaseService {

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;

  public HbaseService(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  // ---------------------------------------------------------------- tables

  public List<HbaseTableInfo> tables() throws Exception {
    return withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            List<HbaseTableInfo> tables = new ArrayList<>();
            for (TableName name : admin.listTableNames()) {
              tables.add(new HbaseTableInfo(name.getNameAsString(), admin.isTableEnabled(name)));
            }
            return tables;
          }
        });
  }

  public List<HbaseColumnFamily> describe(String table) throws Exception {
    return withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            TableDescriptor descriptor = admin.getDescriptor(TableName.valueOf(table));
            List<HbaseColumnFamily> families = new ArrayList<>();
            for (ColumnFamilyDescriptor family : descriptor.getColumnFamilies()) {
              families.add(toColumnFamily(family));
            }
            return families;
          }
        });
  }

  public List<HbaseRegion> regions(String table) throws Exception {
    return withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            List<HbaseRegion> regions = new ArrayList<>();
            for (RegionInfo region : admin.getRegions(TableName.valueOf(table))) {
              regions.add(
                  new HbaseRegion(
                      region.getEncodedName(),
                      Bytes.toStringBinary(region.getStartKey()),
                      Bytes.toStringBinary(region.getEndKey())));
            }
            return regions;
          }
        });
  }

  public void createTable(String table, List<HbaseColumnFamily> families) throws Exception {
    if (families == null || families.isEmpty()) {
      throw new IllegalArgumentException("A table needs at least one column family");
    }
    withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            TableDescriptorBuilder builder =
                TableDescriptorBuilder.newBuilder(TableName.valueOf(table));
            for (HbaseColumnFamily family : families) {
              builder.setColumnFamily(toDescriptor(family));
            }
            admin.createTable(builder.build());
            return null;
          }
        });
  }

  public void deleteTable(String table) throws Exception {
    withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            TableName name = TableName.valueOf(table);
            if (admin.isTableEnabled(name)) {
              admin.disableTable(name);
            }
            admin.deleteTable(name);
            return null;
          }
        });
  }

  public void enableTable(String table) throws Exception {
    withAdmin(admin -> admin.enableTable(TableName.valueOf(table)));
  }

  public void disableTable(String table) throws Exception {
    withAdmin(admin -> admin.disableTable(TableName.valueOf(table)));
  }

  public void truncateTable(String table, boolean preserveSplits) throws Exception {
    withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            TableName name = TableName.valueOf(table);
            if (admin.isTableEnabled(name)) {
              admin.disableTable(name);
            }
            admin.truncateTable(name, preserveSplits);
            return null;
          }
        });
  }

  public void addColumnFamily(String table, HbaseColumnFamily family) throws Exception {
    withAdmin(admin -> admin.addColumnFamily(TableName.valueOf(table), toDescriptor(family)));
  }

  public void modifyColumnFamily(String table, HbaseColumnFamily family) throws Exception {
    withAdmin(admin -> admin.modifyColumnFamily(TableName.valueOf(table), toDescriptor(family)));
  }

  public void deleteColumnFamily(String table, String family) throws Exception {
    withAdmin(admin -> admin.deleteColumnFamily(TableName.valueOf(table), Bytes.toBytes(family)));
  }

  // ------------------------------------------------------------------ rows

  /**
   * Scans a table for the row browser. {@code startRow} is where the scan
   * begins — the browser pages forward by starting the next page just after the
   * last key it showed ({@code startInclusive} false). {@code prefix}, when
   * given, restricts results to keys starting with it and is kept independent of
   * the cursor so a prefix scan can be paged. Columns limit which cells come
   * back, and a raw HBase filter string (the shell grammar) is applied on top.
   */
  public List<HbaseRow> scan(
      String table,
      String startRow,
      boolean startInclusive,
      String prefix,
      int limit,
      List<String> columns,
      String filter)
      throws Exception {
    return withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            Scan scan = new Scan().setLimit(limit);
            if (startRow != null && !startRow.isEmpty()) {
              scan.withStartRow(Bytes.toBytes(startRow), startInclusive);
            }
            selectColumns(scan, columns);
            Filter combined =
                buildFilter(prefix == null || prefix.isEmpty() ? null : prefix, filter);
            if (combined != null) {
              scan.setFilter(combined);
            }
            try (ResultScanner scanner = handle.getScanner(scan)) {
              List<HbaseRow> rows = new ArrayList<>();
              for (Result result : scanner) {
                if (rows.size() >= limit) {
                  break;
                }
                rows.add(toRow(result));
              }
              return rows;
            }
          }
        });
  }

  /** Row keys starting with {@code prefix}, for the search box autocomplete. */
  public List<String> autocompleteRows(String table, String prefix, int limit) throws Exception {
    return withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            Scan scan = new Scan().setLimit(limit);
            if (prefix != null && !prefix.isEmpty()) {
              scan.withStartRow(Bytes.toBytes(prefix));
              scan.setFilter(
                  new FilterList(
                      new PrefixFilter(Bytes.toBytes(prefix)),
                      new FirstKeyOnlyFilter(),
                      new KeyOnlyFilter()));
            } else {
              scan.setFilter(new FilterList(new FirstKeyOnlyFilter(), new KeyOnlyFilter()));
            }
            try (ResultScanner scanner = handle.getScanner(scan)) {
              List<String> keys = new ArrayList<>();
              for (Result result : scanner) {
                if (keys.size() >= limit) {
                  break;
                }
                keys.add(Bytes.toStringBinary(result.getRow()));
              }
              return keys;
            }
          }
        });
  }

  /** The latest value of every (selected) cell in a single row. */
  public HbaseRow row(String table, String rowKey, List<String> columns) throws Exception {
    return withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            Get get = new Get(Bytes.toBytes(rowKey));
            selectColumns(get, columns);
            Result result = handle.get(get);
            return result.isEmpty() ? null : toRow(result);
          }
        });
  }

  /** The version history of one cell, newest first. */
  public List<HbaseCell> cellVersions(String table, String rowKey, String column, int maxVersions)
      throws Exception {
    String[] parts = splitColumn(column);
    return withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            Get get = new Get(Bytes.toBytes(rowKey)).readVersions(maxVersions);
            get.addColumn(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]));
            Result result = handle.get(get);
            List<HbaseCell> versions = new ArrayList<>();
            for (Cell cell :
                result.getColumnCells(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]))) {
              versions.add(toCell(cell));
            }
            return versions;
          }
        });
  }

  // ------------------------------------------------------------- mutations

  /** Inserts or updates the given {@code column -> value} cells of a row. */
  public void putRow(String table, String rowKey, Map<String, String> cells) throws Exception {
    if (cells == null || cells.isEmpty()) {
      throw new IllegalArgumentException("No cells to write");
    }
    withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            Put put = new Put(Bytes.toBytes(rowKey));
            cells.forEach(
                (column, value) -> {
                  String[] parts = splitColumn(column);
                  put.addColumn(
                      Bytes.toBytes(parts[0]),
                      Bytes.toBytes(parts[1]),
                      Bytes.toBytes(value == null ? "" : value));
                });
            handle.put(put);
            return null;
          }
        });
  }

  /** Writes raw bytes to a single cell, for the binary upload path. */
  public void putCellBytes(String table, String rowKey, String column, byte[] value)
      throws Exception {
    String[] parts = splitColumn(column);
    withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            Put put = new Put(Bytes.toBytes(rowKey));
            put.addColumn(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]), value);
            handle.put(put);
            return null;
          }
        });
  }

  public void deleteCells(String table, String rowKey, List<String> columns) throws Exception {
    withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            Delete delete = new Delete(Bytes.toBytes(rowKey));
            for (String column : columns) {
              String[] parts = splitColumn(column);
              delete.addColumns(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]));
            }
            handle.delete(delete);
            return null;
          }
        });
  }

  public void deleteRow(String table, String rowKey) throws Exception {
    withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            handle.delete(new Delete(Bytes.toBytes(rowKey)));
            return null;
          }
        });
  }

  /**
   * Loads a CSV whose header names the columns — the first column is the row
   * key, the rest are {@code family:qualifier} — one row per line. Returns the
   * number of rows written.
   */
  public int bulkUpload(String table, byte[] csv) throws Exception {
    List<String[]> lines = parseCsv(new String(csv, StandardCharsets.UTF_8));
    if (lines.size() < 2) {
      throw new IllegalArgumentException("The CSV needs a header row and at least one data row");
    }
    String[] header = lines.get(0);
    return withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table))) {
            List<Put> batch = new ArrayList<>();
            int written = 0;
            for (int i = 1; i < lines.size(); i++) {
              String[] fields = lines.get(i);
              if (fields.length == 0 || fields[0].isEmpty()) {
                continue;
              }
              Put put = new Put(Bytes.toBytes(fields[0]));
              boolean any = false;
              for (int c = 1; c < fields.length && c < header.length; c++) {
                if (fields[c].isEmpty()) {
                  continue;
                }
                String[] parts = splitColumn(header[c]);
                put.addColumn(
                    Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]), Bytes.toBytes(fields[c]));
                any = true;
              }
              if (any) {
                batch.add(put);
              }
              // Flush in bounded batches so a large upload does not build one
              // enormous RPC or hold the whole file's Puts in memory at once.
              if (batch.size() >= BULK_BATCH) {
                handle.put(batch);
                written += batch.size();
                batch.clear();
              }
            }
            if (!batch.isEmpty()) {
              handle.put(batch);
              written += batch.size();
            }
            return written;
          }
        });
  }

  private static final int BULK_BATCH = 2000;

  // -------------------------------------------------------------- internals

  private static void selectColumns(Scan scan, List<String> columns) {
    if (columns == null) {
      return;
    }
    for (String column : columns) {
      if (column == null || column.isBlank()) {
        continue;
      }
      if (column.contains(":")) {
        String[] parts = splitColumn(column);
        scan.addColumn(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]));
      } else {
        scan.addFamily(Bytes.toBytes(column));
      }
    }
  }

  private static void selectColumns(Get get, List<String> columns) {
    if (columns == null) {
      return;
    }
    for (String column : columns) {
      if (column == null || column.isBlank()) {
        continue;
      }
      if (column.contains(":")) {
        String[] parts = splitColumn(column);
        get.addColumn(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]));
      } else {
        get.addFamily(Bytes.toBytes(column));
      }
    }
  }

  private static Filter buildFilter(String prefix, String filterString) throws Exception {
    List<Filter> filters = new ArrayList<>();
    if (prefix != null && !prefix.isEmpty()) {
      filters.add(new PrefixFilter(Bytes.toBytes(prefix)));
    }
    if (filterString != null && !filterString.isBlank()) {
      filters.add(new ParseFilter().parseFilterString(filterString.trim()));
    }
    if (filters.isEmpty()) {
      return null;
    }
    return filters.size() == 1 ? filters.get(0) : new FilterList(filters);
  }

  private static HbaseRow toRow(Result result) {
    List<HbaseCell> cells = new ArrayList<>();
    for (Cell cell : result.listCells()) {
      cells.add(toCell(cell));
    }
    return new HbaseRow(Bytes.toStringBinary(result.getRow()), cells);
  }

  private static HbaseCell toCell(Cell cell) {
    String column =
        Bytes.toString(CellUtil.cloneFamily(cell))
            + ":"
            + Bytes.toString(CellUtil.cloneQualifier(cell));
    Decoded decoded = decode(CellUtil.cloneValue(cell));
    return new HbaseCell(column, decoded.text(), cell.getTimestamp(), decoded.binary());
  }

  private static String[] splitColumn(String column) {
    int colon = column.indexOf(':');
    if (colon < 0) {
      // A family with no qualifier addresses the family's default (empty) column.
      return new String[] {column, ""};
    }
    return new String[] {column.substring(0, colon), column.substring(colon + 1)};
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

  private static HbaseColumnFamily toColumnFamily(ColumnFamilyDescriptor family) {
    return new HbaseColumnFamily(
        family.getNameAsString(),
        family.getMaxVersions(),
        family.getMinVersions(),
        family.getCompressionType().getName(),
        family.getTimeToLive(),
        family.isBlockCacheEnabled(),
        family.getBloomFilterType().name(),
        family.getDataBlockEncoding().name(),
        family.isInMemory());
  }

  private static ColumnFamilyDescriptor toDescriptor(HbaseColumnFamily family) {
    ColumnFamilyDescriptorBuilder builder =
        ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes(family.name()));
    if (family.maxVersions() != null) {
      builder.setMaxVersions(family.maxVersions());
    }
    if (family.minVersions() != null) {
      builder.setMinVersions(family.minVersions());
    }
    if (family.compression() != null && !family.compression().isBlank()) {
      builder.setCompressionType(Compression.Algorithm.valueOf(family.compression().toUpperCase()));
    }
    if (family.timeToLive() != null) {
      builder.setTimeToLive(family.timeToLive());
    }
    if (family.blockCacheEnabled() != null) {
      builder.setBlockCacheEnabled(family.blockCacheEnabled());
    }
    if (family.bloomFilterType() != null && !family.bloomFilterType().isBlank()) {
      builder.setBloomFilterType(BloomType.valueOf(family.bloomFilterType().toUpperCase()));
    }
    if (family.dataBlockEncoding() != null && !family.dataBlockEncoding().isBlank()) {
      builder.setDataBlockEncoding(
          DataBlockEncoding.valueOf(family.dataBlockEncoding().toUpperCase()));
    }
    if (family.inMemory() != null) {
      builder.setInMemory(family.inMemory());
    }
    return builder.build();
  }

  /** Minimal RFC-4180 CSV: comma-separated, double-quoted fields, "" escapes. */
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

  private void withAdmin(AdminAction action) throws Exception {
    withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            action.apply(admin);
            return null;
          }
        });
  }

  private <T> T withConnection(ConnectionAction<T> action) throws Exception {
    return kerberos.asLoggedInUser(
        () -> {
          Configuration configuration = HBaseConfiguration.create();
          configuration.set("hbase.zookeeper.quorum", properties.hbaseQuorum());
          configuration.set("hadoop.security.authentication", "kerberos");
          configuration.set("hbase.security.authentication", "kerberos");
          configuration.set("hbase.master.kerberos.principal", "hbase/_HOST@TEST.LOCAL");
          configuration.set("hbase.regionserver.kerberos.principal", "hbase/_HOST@TEST.LOCAL");
          configuration.setBoolean(
              "hbase.unsafe.client.kerberos.hostname.disable.reversedns", true);
          try (Connection connection = ConnectionFactory.createConnection(configuration)) {
            return action.apply(connection);
          }
        });
  }

  @FunctionalInterface
  private interface ConnectionAction<T> {
    T apply(Connection connection) throws Exception;
  }

  @FunctionalInterface
  private interface AdminAction {
    void apply(Admin admin) throws Exception;
  }
}
