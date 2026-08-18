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
package com.kudos.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which service modules are active. A disabled module is hidden from the sidebar
 * and its screens and API endpoints answer 404, so an app pointed at a cluster
 * that lacks a service simply does not offer it. Every flag defaults to enabled.
 *
 * @param editor Kyuubi Spark SQL editor ({@code /editor}, {@code /api/sql/**})
 * @param files HDFS file browser ({@code /filebrowser}, {@code /api/hdfs/**})
 * @param ozone Ozone browser ({@code /ozone}, {@code /api/ozone/**})
 * @param hbase HBase browser ({@code /hbase}, {@code /api/hbase/**})
 * @param jobs Spark jobs and the proxied Spark UI ({@code /jobs}, {@code /api/spark/**})
 */
@ConfigurationProperties(prefix = "kudos.features")
public record FeaturesProperties(
    Boolean editor, Boolean files, Boolean ozone, Boolean hbase, Boolean jobs) {

  public FeaturesProperties {
    editor = editor == null ? Boolean.TRUE : editor;
    files = files == null ? Boolean.TRUE : files;
    ozone = ozone == null ? Boolean.TRUE : ozone;
    hbase = hbase == null ? Boolean.TRUE : hbase;
    jobs = jobs == null ? Boolean.TRUE : jobs;
  }
}
