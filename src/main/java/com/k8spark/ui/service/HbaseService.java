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
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
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
          try (Connection connection = ConnectionFactory.createConnection(configuration);
              Admin admin = connection.getAdmin()) {
            return Arrays.stream(admin.listTableNames())
                .map(TableName::getNameAsString)
                .toList();
          }
        });
  }
}
