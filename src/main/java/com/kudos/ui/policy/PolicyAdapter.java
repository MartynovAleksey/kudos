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

package com.kudos.ui.policy;

import java.util.List;

/** Adapter boundary for a native policy store; KUDOS does not persist effective rights. */
public interface PolicyAdapter {
  String tool();
  List<PolicyModels.Policy> list();
  void grant(PolicyModels.Mutation mutation);
  void revoke(PolicyModels.Mutation mutation);
}
