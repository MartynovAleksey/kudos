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

import com.k8spark.ui.service.HbaseService;
import com.k8spark.ui.service.HdfsService;
import com.k8spark.ui.service.KyuubiService;
import com.k8spark.ui.service.OzoneService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ClusterController {

  private final HdfsService hdfs;
  private final KyuubiService kyuubi;
  private final HbaseService hbase;
  private final OzoneService ozone;

  public ClusterController(
      HdfsService hdfs,
      KyuubiService kyuubi,
      HbaseService hbase,
      OzoneService ozone) {
    this.hdfs = hdfs;
    this.kyuubi = kyuubi;
    this.hbase = hbase;
    this.ozone = ozone;
  }

  @GetMapping("/hdfs")
  String hdfs(@RequestParam(defaultValue = "/") String path) throws Exception {
    return hdfs.list(path);
  }

  @PostMapping("/sql")
  List<Map<String, Object>> sql(@Valid @RequestBody SqlRequest request) throws Exception {
    return kyuubi.query(request.sql());
  }

  @GetMapping("/hbase/tables")
  List<String> tables() throws Exception {
    return hbase.tables();
  }

  @GetMapping("/ozone")
  List<String> ozone(@RequestParam(defaultValue = "/") String path) throws Exception {
    return ozone.list(path);
  }

  record SqlRequest(@NotBlank String sql) {}
}
