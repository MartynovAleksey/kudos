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
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.stereotype.Service;

@Service
public class OzoneService {

  private final ClusterProperties properties;
  private final KerberosExecutor kerberos;

  public OzoneService(ClusterProperties properties, KerberosExecutor kerberos) {
    this.properties = properties;
    this.kerberos = kerberos;
  }

  public List<String> list(String path) throws Exception {
    return kerberos.asLoggedInUser(
        () -> {
          Configuration configuration = new Configuration();
          configuration.set("hadoop.security.authentication", "kerberos");
          configuration.set("ozone.security.enabled", "true");
          configuration.set(
              "fs.ofs.impl", "org.apache.hadoop.fs.ozone.RootedOzoneFileSystem");
          try (FileSystem fileSystem =
              FileSystem.get(URI.create(properties.ozoneOfsUri()), configuration)) {
            return Arrays.stream(fileSystem.listStatus(new Path(path)))
                .map(FileStatus::getPath)
                .map(Path::toString)
                .toList();
          }
        });
  }
}
