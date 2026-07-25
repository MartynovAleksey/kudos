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

import com.k8spark.ui.config.UiProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts UI-wide configuration — currently the optional top banner — on every
 * rendered screen so the layout can show it without each controller passing it.
 */
@ControllerAdvice(assignableTypes = UiController.class)
public class UiModelAdvice {

  private final UiProperties ui;

  public UiModelAdvice(UiProperties ui) {
    this.ui = ui;
  }

  @ModelAttribute("bannerHtml")
  String bannerHtml() {
    return ui.bannerHtml();
  }
}
