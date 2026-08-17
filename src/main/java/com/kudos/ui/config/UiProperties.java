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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UI-level configuration, separate from the cluster service addresses.
 *
 * @param bannerHtml optional HTML shown as a banner across the top of every
 *     screen, the way Hue's {@code banner_top_html} works. It is rendered
 *     unescaped and comes only from this trusted configuration file, never from
 *     a user.
 * @param toolVisibilityFile optional JSON file where an administrator's choice
 *     of which left-panel tools are visible is persisted so it applies to every
 *     user. Blank keeps the choice in memory only (lost on restart), which is
 *     the safe default for local runs and tests.
 * @param engineVisibilityFile optional JSON file, like {@code toolVisibilityFile},
 *     for which SQL engines (Spark, Flink, Trino, StarRocks) are shown in the
 *     editor. Blank keeps the choice in memory only.
 */
@ConfigurationProperties(prefix = "kudos.ui")
public record UiProperties(
    String bannerHtml, String toolVisibilityFile, String engineVisibilityFile) {}
