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

import com.kudos.ui.config.FeaturesProperties;
import com.kudos.ui.service.SparkApplicationAccessService;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the application screens. Each one is a thin shell: the data is fetched
 * by the browser from {@link ClusterController} under the same session.
 */
@Controller
public class UiController {

  private final FeaturesProperties features;
  private final SparkApplicationAccessService sparkAccess;

  public UiController(FeaturesProperties features, SparkApplicationAccessService sparkAccess) {
    this.features = features;
    this.sparkAccess = sparkAccess;
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }

  @GetMapping("/")
  String index() {
    // Land on the first enabled screen, so a disabled editor is not a dead end.
    if (features.editor()) {
      return "redirect:/editor";
    }
    if (features.files()) {
      return "redirect:/filebrowser";
    }
    if (features.ozone()) {
      return "redirect:/ozone";
    }
    if (features.hbase()) {
      return "redirect:/hbase";
    }
    if (features.jobs()) {
      return "redirect:/jobs";
    }
    return "redirect:/docs";
  }

  @GetMapping("/editor")
  String editor(Model model) {
    model.addAttribute("app", "editor");
    return "editor";
  }

  @GetMapping("/filebrowser")
  String filebrowser(@RequestParam(defaultValue = "/") String path, Model model) {
    model.addAttribute("app", "filebrowser");
    model.addAttribute("path", path);
    return "filebrowser";
  }

  @GetMapping("/ozone")
  String ozone(@RequestParam(defaultValue = "/") String path, Model model) {
    model.addAttribute("app", "ozone");
    model.addAttribute("path", path);
    return "ozone";
  }

  @GetMapping("/hbase")
  String hbase(Model model) {
    model.addAttribute("app", "hbase");
    return "hbase";
  }

  @GetMapping("/jobs")
  String jobs(@RequestParam(required = false) String type, Model model) {
    model.addAttribute("app", "jobs");
    // The active application-type tab is carried in the URL so a return from a
    // job detail lands on the same tab instead of the default one.
    model.addAttribute("activeType", "flink".equals(type) ? "flink" : "spark");
    return "jobs";
  }

  @GetMapping("/docs")
  String docs(Model model) {
    model.addAttribute("app", "docs");
    return "docs";
  }

  @GetMapping("/jobs/{applicationId}")
  String job(@PathVariable String applicationId, Model model, Authentication authentication) throws Exception {
    if (!sparkAccess.canView(authentication, applicationId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    model.addAttribute("app", "jobs");
    model.addAttribute("applicationId", applicationId);
    return "job";
  }

  /**
   * Embeds a Flink job's dashboard inside the KUDOS chrome, mirroring the Spark
   * job page. {@code scope} selects the proxied upstream: {@code jobmanager} for
   * a running job, {@code history} for a finished one. Flink jobs are not owned
   * by a KUDOS user, so this is administrator-only like the proxy itself.
   */
  @GetMapping("/flink/{scope}/{jid}")
  String flinkJob(
      @PathVariable String scope, @PathVariable String jid, Model model, Authentication authentication) {
    if (!sparkAccess.isAdministrator(authentication)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    if (!scope.equals("jobmanager") && !scope.equals("history")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    model.addAttribute("app", "jobs");
    model.addAttribute("jid", jid);
    // The Flink dashboard is a hash-routed SPA, so the job path rides in the
    // fragment; its route segment is "completed" on the History Server and
    // "running" on the JobManager. The embedded frame carries ?embedded so the
    // proxy strips Flink's own left sider (the KUDOS chrome already provides
    // navigation); the new-tab link opens the full standalone dashboard.
    String state = scope.equals("history") ? "completed" : "running";
    String deepLink = "#/job/" + state + "/" + jid + "/overview";
    model.addAttribute("frameUrl", "/flink-ui/" + scope + "/?embedded" + deepLink);
    model.addAttribute("rawUrl", "/flink-ui/" + scope + "/" + deepLink);
    return "flink";
  }
}
