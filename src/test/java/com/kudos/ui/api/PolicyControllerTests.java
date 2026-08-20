/*
 * Copyright 2026 Aleksey Martynov and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.kudos.ui.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kudos.ui.audit.AuditService;
import com.kudos.ui.policy.PolicyModels;
import com.kudos.ui.service.PolicyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "kudos.api.enabled=true",
    "kudos.cluster.kerberos-principal={user}@EXAMPLE.COM"
})
@AutoConfigureMockMvc
class PolicyControllerTests {
  @Autowired MockMvc mockMvc;
  @MockitoBean PolicyService policies;
  @MockitoBean AuditService audit;

  @Test
  @WithMockUser(username = "officer", authorities = "ROLE_SECURITY_OFFICER")
  void officerCanListPolicies() throws Exception {
    var policy = new PolicyModels.Policy("gravitino", "metalake_demo.catalog_iceberg",
        "role:analyst", List.of("USE_CATALOG"), "ALLOW", "Gravitino");
    org.mockito.Mockito.when(policies.listAll()).thenReturn(List.of(policy));

    mockMvc.perform(get("/ui-api/security/policies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].tool").value("gravitino"));
  }

  @Test
  @WithMockUser(username = "user", authorities = "ROLE_USER")
  void regularUserCannotListOrMutatePolicies() throws Exception {
    mockMvc.perform(get("/ui-api/security/policies")).andExpect(status().isForbidden());
    mockMvc.perform(post("/ui-api/security/policies/gravitino/grant").with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"role\":\"reader\",\"subject\":\"user:analyst\",\"resource\":\"catalog.demo\",\"privilege\":\"USE_CATALOG\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(policies, audit);
  }

  @Test
  @WithMockUser(username = "admin", authorities = "ROLE_ADMINISTRATOR")
  void administratorCannotUsePolicyApi() throws Exception {
    mockMvc.perform(get("/ui-api/security/policies")).andExpect(status().isForbidden());
    verifyNoInteractions(policies, audit);
  }

  @Test
  @WithMockUser(username = "officer", authorities = "ROLE_SECURITY_OFFICER")
  void invalidMutationReturnsBadRequest() throws Exception {
    doThrow(new IllegalArgumentException("invalid mutation"))
        .when(policies).mutate(any(), any());

    mockMvc.perform(post("/ui-api/security/policies/gravitino/grant").with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"role\":\"reader\",\"subject\":\"user:analyst\",\"resource\":\"catalog.demo\",\"privilege\":\"BAD\"}"))
        .andExpect(status().isBadRequest());
    verify(audit).recordPolicyMutation("grant", "gravitino", "user:analyst", "catalog.demo", false);
  }

  @Test
  @WithMockUser(username = "officer", authorities = "ROLE_SECURITY_OFFICER")
  void backendFailureReturnsBadGatewayAndAuditsFailure() throws Exception {
    doThrow(new IllegalStateException("Gravitino unavailable"))
        .when(policies).mutate(any(), any());

    mockMvc.perform(post("/ui-api/security/policies/gravitino/grant").with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"role\":\"reader\",\"subject\":\"user:analyst\",\"resource\":\"catalog.demo\",\"privilege\":\"USE_CATALOG\"}"))
        .andExpect(status().isBadGateway());
    verify(audit).recordPolicyMutation("grant", "gravitino", "user:analyst", "catalog.demo", false);
  }
}
