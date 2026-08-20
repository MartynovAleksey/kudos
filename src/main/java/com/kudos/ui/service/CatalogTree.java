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
package com.kudos.ui.service;

import java.util.List;

/**
 * The shared Iceberg catalog as a catalog → schema → table tree for the Editor
 * panel. When the catalog cannot be reached, {@code available} is false and
 * {@code error} carries a short diagnostic so the panel can explain itself
 * without breaking the Editor.
 */
public record CatalogTree(String catalog, String status, List<Schema> schemas, String error) {

  /** One Iceberg namespace and the tables it holds. */
  public record Schema(String name, List<String> tables) {}

  /** Kept as a derived property so existing Editor clients remain compatible. */
  public boolean available() {
    return !"unavailable".equals(status) && !"forbidden".equals(status);
  }

  static CatalogTree available(String catalog, List<Schema> schemas) {
    return new CatalogTree(catalog, "available", List.copyOf(schemas), null);
  }

  static CatalogTree forbidden(String catalog) {
    return new CatalogTree(catalog, "forbidden", List.of(), "CATALOG_ACCESS_DENIED");
  }

  static CatalogTree unavailable(String catalog, String error) {
    return new CatalogTree(catalog, "unavailable", List.of(), error);
  }
}
