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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.springframework.stereotype.Service;

@Service
public class HbaseService {

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;

  public HbaseService(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  public List<String> tables() throws Exception {
    return withConnection(
        connection -> {
          try (Admin admin = connection.getAdmin()) {
            return Arrays.stream(admin.listTableNames())
                .map(TableName::getNameAsString)
                .toList();
          }
        });
  }

  /** Scans the first rows of a table for the row browser. */
  public List<HbaseRow> scan(String table, int limit) throws Exception {
    return withConnection(
        connection -> {
          try (Table handle = connection.getTable(TableName.valueOf(table));
              ResultScanner scanner = handle.getScanner(new Scan().setLimit(limit))) {
            List<HbaseRow> rows = new ArrayList<>();
            for (Result result : scanner) {
              if (rows.size() >= limit) {
                break;
              }
              Map<String, String> cells = new LinkedHashMap<>();
              for (Cell cell : result.listCells()) {
                String qualifier =
                    new String(CellUtil.cloneFamily(cell), StandardCharsets.UTF_8)
                        + ":"
                        + new String(CellUtil.cloneQualifier(cell), StandardCharsets.UTF_8);
                cells.put(
                    qualifier,
                    new String(CellUtil.cloneValue(cell), StandardCharsets.UTF_8));
              }
              rows.add(
                  new HbaseRow(
                      new String(result.getRow(), StandardCharsets.UTF_8), cells));
            }
            return rows;
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
}
