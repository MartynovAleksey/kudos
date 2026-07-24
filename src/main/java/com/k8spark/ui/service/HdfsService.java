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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.KerberosAuthenticator;
import org.springframework.stereotype.Service;

@Service
public class HdfsService {

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;

  public HdfsService(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  public String list(String path) throws Exception {
    return kerberos.asLoggedInUser(
        () -> {
          AuthenticatedURL.Token token = new AuthenticatedURL.Token();
          URL url =
              new URL(properties.webhdfsUrl() + "/webhdfs/v1" + path + "?op=LISTSTATUS");
          var connection =
              new AuthenticatedURL(new KerberosAuthenticator()).openConnection(url, token);
          connection.setRequestMethod("GET");
          if (connection.getResponseCode() >= 400) {
            throw new IllegalStateException(
                "WebHDFS returned " + connection.getResponseCode());
          }
          return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        });
  }
}
