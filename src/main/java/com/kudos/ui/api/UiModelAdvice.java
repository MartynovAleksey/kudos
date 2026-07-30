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
import com.kudos.ui.config.UiProperties;
import com.kudos.ui.security.RoleAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts UI-wide configuration on every rendered screen — the optional top banner
 * and the enabled feature modules — so the layout can show or hide chrome
 * without each controller passing it.
 */
@ControllerAdvice(assignableTypes = UiController.class)
public class UiModelAdvice {

  private final UiProperties ui;
  private final FeaturesProperties features;
  private final RoleAccess roles;

  public UiModelAdvice(UiProperties ui, FeaturesProperties features, RoleAccess roles) {
    this.ui = ui;
    this.features = features;
    this.roles = roles;
  }

  @ModelAttribute("bannerHtml")
  String bannerHtml() {
    return ui.bannerHtml();
  }

  @ModelAttribute("features")
  FeaturesProperties features() {
    return features;
  }

  @ModelAttribute("administrator")
  boolean administrator(Authentication authentication) {
    return roles.isAdministrator(authentication);
  }
}
