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

import com.kudos.ui.config.SparkRegistrationProperties;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The live Spark applications, as their own drivers reported them through
 * {@code /engine-api/spark/running}. The history server only learns about an
 * application once it has finished, so this is the only source for a job a user
 * is watching right now.
 *
 * <p>An entry leaves in one of three ways: the driver removes it on shutdown,
 * the proxy removes it after the driver stops answering, or it ages out. Held in
 * memory, like the Kyuubi sessions and query history it sits beside.
 */
@Service
public class RunningSparkApplications {

  private static final Logger LOG = LoggerFactory.getLogger(RunningSparkApplications.class);

  private final Map<String, RunningSparkApplication> applications = new ConcurrentHashMap<>();
  private final SparkRegistrationProperties properties;

  public RunningSparkApplications(SparkRegistrationProperties properties) {
    this.properties = properties;
  }

  /**
   * @throws IllegalArgumentException when the reported UI address is not one
   *     this application is allowed to reach; the address comes from outside, so
   *     it never becomes an arbitrary outbound request.
   */
  public void register(RunningSparkApplication application) {
    String host = hostOf(application.uiUrl());
    if (host == null || !allows(host)) {
      throw new IllegalArgumentException("Spark UI address is not allowed: " + application.uiUrl());
    }
    applications.put(application.appId(), application);
    LOG.info(
        "Running Spark application {} registered by {} at {}",
        application.appId(),
        application.user(),
        application.uiUrl());
  }

  public void unregister(String appId) {
    if (applications.remove(appId) != null) {
      LOG.info("Running Spark application {} unregistered", appId);
    }
  }

  public Optional<RunningSparkApplication> find(String appId) {
    return Optional.ofNullable(applications.get(appId)).filter(this::isFresh);
  }

  /**
   * Every live application, newest first, expired ones dropped.
   *
   * <p>ponytail: an engine killed outright (pod restart, OOM, kill -9) keeps its
   * row until the TTL, or until someone opens it and the proxy finds the driver
   * gone. If that stale row becomes a nuisance, probe each registered URL here
   * instead of shortening the TTL for everyone.
   */
  public List<RunningSparkApplication> applications() {
    applications.values().removeIf(application -> !isFresh(application));
    return applications.values().stream()
        .sorted((left, right) -> Long.compare(right.startTimeMs(), left.startTimeMs()))
        .toList();
  }

  private boolean isFresh(RunningSparkApplication application) {
    return System.currentTimeMillis() - application.registeredAtMs()
        < properties.runningTtl().toMillis();
  }

  private static String hostOf(String uiUrl) {
    try {
      URI uri = URI.create(uiUrl);
      String scheme = uri.getScheme();
      if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
        return null;
      }
      return uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  /** Exact name, {@code *.suffix} or an IPv4 prefix such as {@code 10.} for pod addresses. */
  private boolean allows(String host) {
    for (String pattern : properties.uiHosts()) {
      String allowed = pattern.trim().toLowerCase(Locale.ROOT);
      if (allowed.isEmpty()) {
        continue;
      }
      if (allowed.startsWith("*.")) {
        if (host.endsWith(allowed.substring(1))) {
          return true;
        }
      } else if (allowed.endsWith(".")) {
        if (host.startsWith(allowed)) {
          return true;
        }
      } else if (host.equals(allowed)) {
        return true;
      }
    }
    return false;
  }
}
