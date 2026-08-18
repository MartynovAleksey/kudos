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
package com.kudos.ui.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Records what each user did. Every event goes to the {@code audit} logger,
 * which logback writes to its own rolling file, and — when Kafka is enabled — is
 * also published to a topic so a central collector can consume it. Kafka is off
 * by default so the stand needs no broker; the file audit always runs.
 */
@Service
public class AuditService {

  private static final Logger AUDIT = LoggerFactory.getLogger("audit");

  private final ObjectProvider<KafkaTemplate<String, String>> kafka;
  private final boolean kafkaEnabled;
  private final String topic;
  private final ObjectMapper mapper = new ObjectMapper();

  public AuditService(
      ObjectProvider<KafkaTemplate<String, String>> kafka,
      @Value("${kudos.audit.kafka-enabled:false}") boolean kafkaEnabled,
      @Value("${kudos.audit.kafka-topic:kudos-audit}") String topic) {
    this.kafka = kafka;
    this.kafkaEnabled = kafkaEnabled;
    this.topic = topic;
  }

  public void record(String method, String path, int status) {
    AuditEvent event =
        new AuditEvent(Instant.now().toString(), currentUser(), method, path, status);
    String json = toJson(event);
    AUDIT.info(json);
    if (kafkaEnabled) {
      KafkaTemplate<String, String> template = kafka.getIfAvailable();
      if (template != null) {
        try {
          template.send(topic, event.user(), json);
        } catch (Exception publishFailure) {
          // Never let an audit-transport failure break the request it describes.
          AUDIT.warn("audit kafka publish failed: {}", publishFailure.getMessage());
        }
      }
    }
  }

  private static String currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? "anonymous" : authentication.getName();
  }

  private String toJson(AuditEvent event) {
    try {
      return mapper.writeValueAsString(event);
    } catch (Exception unexpected) {
      return "{\"user\":\"" + event.user() + "\",\"path\":\"" + event.path() + "\"}";
    }
  }
}
