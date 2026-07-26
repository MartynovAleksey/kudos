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

package com.kudos.ui.config;

import com.kudos.ui.audit.AuditInterceptor;
import com.kudos.ui.audit.AuditService;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the vendored Hue stylesheets and fonts under the same paths Hue itself
 * uses, so the upstream CSS resolves its relative font references unchanged, and
 * audits every API request.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final AuditService audit;
  private final FeaturesProperties features;

  public WebConfig(AuditService audit, FeaturesProperties features) {
    this.audit = audit;
    this.features = features;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // Gate disabled modules before anything else, then audit what runs.
    registry.addInterceptor(new FeatureGate(features));
    registry.addInterceptor(new AuditInterceptor(audit)).addPathPatterns("/api/**");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/static/desktop/**")
        .addResourceLocations("classpath:/hue-upstream/desktop/static/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    registry
        .addResourceHandler("/static/app/**")
        .addResourceLocations("classpath:/app-static/")
        .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
  }
}
