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
package com.kudos.ui.api;

import com.kudos.ui.service.CatalogTree;
import com.kudos.ui.service.CatalogTreeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the shared Iceberg catalog tree that the Editor lists in its side panel. */
@RestController
@RequestMapping({"/api", "/ui-api"})
public class CatalogController {

  private final CatalogTreeService catalog;

  public CatalogController(CatalogTreeService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/catalog/tree")
  CatalogTree tree() {
    return catalog.tree();
  }
}
