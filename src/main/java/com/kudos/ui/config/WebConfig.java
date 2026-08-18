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

import com.kudos.ui.audit.AuditInterceptor;
import com.kudos.ui.audit.AuditService;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the vendored third-party webfonts (Font Awesome and Roboto) under a
 * fixed path so their CSS resolves its relative font references unchanged, the
 * application's own CSS/JS/assets, and audits every API request.
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
    registry
        .addInterceptor(new AuditInterceptor(audit))
        .addPathPatterns("/api/**", "/ui-api/**");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        // Vendored third-party webfonts only: Font Awesome (icons) and Roboto.
        .addResourceHandler("/static/desktop/**")
        .addResourceLocations("classpath:/webui-vendor/desktop/static/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    registry
        // The application's own CSS/JS/assets are not content-hashed, so a
        // long max-age would leave browsers on a stale build (visual defects)
        // for up to that window after a deploy. no-cache keeps them cached but
        // revalidated on every load: unchanged files answer 304 from
        // Last-Modified, a new build is picked up immediately.
        .addResourceHandler("/static/app/**")
        .addResourceLocations("classpath:/app-static/")
        .setCacheControl(CacheControl.noCache());
  }
}
