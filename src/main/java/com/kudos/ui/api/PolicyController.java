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

import com.kudos.ui.audit.AuditService;
import com.kudos.ui.policy.PolicyModels;
import com.kudos.ui.security.RoleAccess;
import com.kudos.ui.service.PolicyService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Server-side policy boundary. Browser visibility is not an authorization control. */
@RestController
@RequestMapping("/ui-api/security/policies")
public class PolicyController {
  private final PolicyService policies;
  private final RoleAccess roles;
  private final AuditService audit;

  public PolicyController(PolicyService policies, RoleAccess roles, AuditService audit) {
    this.policies = policies;
    this.roles = roles;
    this.audit = audit;
  }

  @GetMapping
  List<PolicyModels.Policy> listAll(Authentication authentication) {
    requireOfficer(authentication);
    try { return policies.listAll(); }
    catch (RuntimeException failure) { throw safeFailure(failure); }
  }

  @GetMapping("/{tool}")
  List<PolicyModels.Policy> list(@PathVariable String tool, Authentication authentication) {
    requireOfficer(authentication);
    try { return policies.list(tool); }
    catch (RuntimeException failure) { throw safeFailure(failure); }
  }

  @PostMapping("/{tool}/grant")
  void grant(@PathVariable String tool, @RequestBody PolicyModels.Mutation mutation,
      Authentication authentication) {
    mutate(tool, mutation, authentication, "grant");
  }

  @PostMapping("/{tool}/revoke")
  void revoke(@PathVariable String tool, @RequestBody PolicyModels.Mutation mutation,
      Authentication authentication) {
    mutate(tool, mutation, authentication, "revoke");
  }

  private void mutate(String tool, PolicyModels.Mutation mutation, Authentication authentication,
      String operation) {
    requireOfficer(authentication);
    if (mutation == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mutation is required");
    PolicyModels.Mutation normalized = new PolicyModels.Mutation(operation, mutation.role(),
        mutation.subject(), mutation.resource(), mutation.privilege());
    int status = 200;
    try {
      policies.mutate(tool, normalized);
    } catch (RuntimeException failure) {
      status = failure instanceof IllegalArgumentException ? 400 : 502;
      audit.recordPolicyMutation(operation, tool, normalized.subject(), normalized.resource(), status == 200);
      throw safeFailure(failure, status);
    }
    audit.recordPolicyMutation(operation, tool, normalized.subject(), normalized.resource(), true);
  }

  private void requireOfficer(Authentication authentication) {
    if (!roles.isSecurityOfficer(authentication)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private ResponseStatusException safeFailure(RuntimeException failure) { return safeFailure(failure, 502); }
  private ResponseStatusException safeFailure(RuntimeException failure, int status) {
    return new ResponseStatusException(HttpStatus.valueOf(status),
        status == 400 ? failure.getMessage() : "Policy service unavailable");
  }
}
