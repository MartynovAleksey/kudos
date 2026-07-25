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

package com.k8spark.ui.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-memory brute-force guard: counts consecutive failed logins per username and
 * locks the account for a cooldown once the limit is reached, so a password
 * cannot be tried indefinitely. A successful login clears the count.
 */
@Service
public class LoginAttemptService {

  private final int maxAttempts;
  private final Duration lockout;
  private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

  public LoginAttemptService(
      @Value("${k8spark.security.max-login-attempts:5}") int maxAttempts,
      @Value("${k8spark.security.lockout-minutes:15}") long lockoutMinutes) {
    this.maxAttempts = maxAttempts;
    this.lockout = Duration.ofMinutes(lockoutMinutes);
  }

  public boolean isBlocked(String username) {
    Attempt attempt = attempts.get(key(username));
    return attempt != null && attempt.lockedUntil != null && Instant.now().isBefore(attempt.lockedUntil);
  }

  public void loginFailed(String username) {
    attempts.compute(
        key(username),
        (user, existing) -> {
          Attempt attempt = existing == null ? new Attempt() : existing;
          if (attempt.lockedUntil != null && Instant.now().isAfter(attempt.lockedUntil)) {
            attempt = new Attempt();
          }
          attempt.count++;
          if (attempt.count >= maxAttempts) {
            attempt.lockedUntil = Instant.now().plus(lockout);
          }
          return attempt;
        });
  }

  public void loginSucceeded(String username) {
    attempts.remove(key(username));
  }

  /** Attempts left before a lock; zero once locked. */
  public int remaining(String username) {
    if (isBlocked(username)) {
      return 0;
    }
    Attempt attempt = attempts.get(key(username));
    int used = attempt == null ? 0 : attempt.count;
    return Math.max(maxAttempts - used, 0);
  }

  public long lockSecondsRemaining(String username) {
    Attempt attempt = attempts.get(key(username));
    if (attempt == null || attempt.lockedUntil == null) {
      return 0;
    }
    long seconds = Duration.between(Instant.now(), attempt.lockedUntil).getSeconds();
    return Math.max(seconds, 0);
  }

  private static String key(String username) {
    return username == null ? "" : username.trim().toLowerCase();
  }

  private static final class Attempt {
    private int count;
    private Instant lockedUntil;
  }
}
