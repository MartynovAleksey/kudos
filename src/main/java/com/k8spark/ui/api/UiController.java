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

import com.k8spark.ui.config.FeaturesProperties;
import org.springframework.stereotype.Controller;
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

  public UiController(FeaturesProperties features) {
    this.features = features;
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
  String jobs(Model model) {
    model.addAttribute("app", "jobs");
    return "jobs";
  }

  @GetMapping("/docs")
  String docs(Model model) {
    model.addAttribute("app", "docs");
    return "docs";
  }

  @GetMapping("/jobs/{applicationId}")
  String job(@PathVariable String applicationId, Model model) {
    model.addAttribute("app", "jobs");
    model.addAttribute("applicationId", applicationId);
    return "job";
  }
}
