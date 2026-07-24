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
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    return listEntries(path).stream().map(FileEntry::path).toList();
  }

  public List<FileEntry> listEntries(String path) throws Exception {
    return withFileSystem(
        fileSystem ->
            Arrays.stream(fileSystem.listStatus(new Path(path)))
                .map(OzoneService::toEntry)
                .toList());
  }

  /** Reads the head of an object for the preview pane. */
  public String preview(String path, int maxBytes) throws Exception {
    return withFileSystem(
        fileSystem -> {
          try (InputStream stream = fileSystem.open(new Path(path))) {
            return new String(stream.readNBytes(maxBytes), StandardCharsets.UTF_8);
          }
        });
  }

  private <T> T withFileSystem(FileSystemAction<T> action) throws Exception {
    return kerberos.asLoggedInUser(
        () -> {
          Configuration configuration = new Configuration();
          addClusterConfiguration(configuration);
          configuration.set("hadoop.security.authentication", "kerberos");
          configuration.set("ozone.security.enabled", "true");
          configuration.set("fs.ofs.impl", "org.apache.hadoop.fs.ozone.RootedOzoneFileSystem");
          // The stand runs a single OM. Left at its default the client spends
          // minutes failing over to the same node before surfacing an error,
          // and a page request just hangs.
          configuration.setInt("ozone.client.failover.max.attempts", 2);
          try (FileSystem fileSystem =
              FileSystem.get(URI.create(properties.ozoneOfsUri()), configuration)) {
            return action.apply(fileSystem);
          }
        });
  }

  /**
   * Layers Ozone's own {@code core-site.xml} and {@code ozone-site.xml} on top
   * of the defaults, so the service principals and addresses stay defined in
   * one place instead of being restated here.
   */
  private void addClusterConfiguration(Configuration configuration) {
    String directory = properties.ozoneConfDir();
    if (directory == null || directory.isBlank()) {
      return;
    }
    for (String name : new String[] {"core-site.xml", "ozone-site.xml"}) {
      File file = new File(directory, name);
      if (file.canRead()) {
        configuration.addResource(new Path(file.getAbsolutePath()));
      }
    }
  }

  private static FileEntry toEntry(FileStatus status) {
    Path path = status.getPath();
    return new FileEntry(
        path.getName(),
        path.toUri().getPath(),
        status.isDirectory(),
        status.getLen(),
        status.getOwner(),
        status.getGroup(),
        status.getPermission().toString(),
        status.getModificationTime());
  }

  @FunctionalInterface
  private interface FileSystemAction<T> {
    T apply(FileSystem fileSystem) throws Exception;
  }
}
