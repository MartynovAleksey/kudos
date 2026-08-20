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

package com.kudos.ui.service;

import com.kudos.ui.policy.PolicyAdapter;
import com.kudos.ui.policy.PolicyModels;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {
  private final PolicyAdapter gravitino;

  public PolicyService(PolicyAdapter gravitino) { this.gravitino = gravitino; }

  public List<PolicyModels.Policy> list(String tool) {
    if (!gravitino.tool().equalsIgnoreCase(tool)) throw new IllegalArgumentException("Unsupported policy tool");
    return gravitino.list();
  }

  public List<PolicyModels.Policy> listAll() { return gravitino.list(); }

  public void mutate(String tool, PolicyModels.Mutation mutation) {
    if (!gravitino.tool().equalsIgnoreCase(tool)) throw new IllegalArgumentException("Unsupported policy tool");
    if ("grant".equalsIgnoreCase(mutation.operation())) gravitino.grant(mutation);
    else if ("revoke".equalsIgnoreCase(mutation.operation())) gravitino.revoke(mutation);
    else throw new IllegalArgumentException("Unsupported policy operation");
  }
}
