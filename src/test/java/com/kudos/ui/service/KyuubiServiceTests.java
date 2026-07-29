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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kudos.ui.config.ClusterProperties;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hive.jdbc.HiveConnection;
import org.apache.hive.service.rpc.thrift.THandleIdentifier;
import org.apache.hive.service.rpc.thrift.TSessionHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class KyuubiServiceTests {

  private Driver driver;

  @AfterEach
  void cleanUp() throws Exception {
    SecurityContextHolder.clearContext();
    if (driver != null) {
      DriverManager.deregisterDriver(driver);
    }
  }

  @Test
  void createsTabsImmediatelyAndStartsIndependentEnginesInBackground() throws Exception {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    HiveConnection firstConnection = connection(firstId);
    HiveConnection secondConnection = connection(secondId);

    driver = mock(Driver.class);
    CountDownLatch firstConnect = new CountDownLatch(1);
    CountDownLatch releaseFirstConnect = new CountDownLatch(1);
    when(driver.connect(any(), any()))
        .thenAnswer(
            invocation -> {
              firstConnect.countDown();
              releaseFirstConnect.await(2, TimeUnit.SECONDS);
              return firstConnection;
            })
        .thenReturn(secondConnection);
    DriverManager.registerDriver(driver);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", "", List.of()));

    KerberosExecutor kerberos =
        new KerberosExecutor() {
          @Override
          public <T> T asLoggedInUser(PrivilegedExceptionAction<T> action) throws Exception {
            return action.run();
          }
        };
    ClusterProperties properties =
        new ClusterProperties(
            "", "jdbc:kyuubi-test:", "http://kyuubi-rest", "", "", "", "", "");

    KyuubiRestClient rest = mock(KyuubiRestClient.class);
    KyuubiService service = new KyuubiService(properties, kerberos, rest);
    KyuubiSessionInfo first = service.start("first", "");

    assertEquals("STARTING", first.state());
    assertTrue(firstConnect.await(1, TimeUnit.SECONDS));
    releaseFirstConnect.countDown();
    KyuubiSessionInfo readyFirst = awaitReady(service, first.id());
    KyuubiSessionInfo second = service.start("second", "");
    KyuubiSessionInfo readySecond = awaitReady(service, second.id());

    assertNotEquals(firstId.toString(), first.id());
    assertNotEquals(secondId.toString(), second.id());
    assertEquals(firstId.toString(), readyFirst.kyuubiSessionId());
    assertEquals(secondId.toString(), readySecond.kyuubiSessionId());
    assertTrue(readySecond.active());
    assertEquals(2, service.sessions().size());
    assertFalse(service.sessions().get(0).active());

    KyuubiRestClient.Snapshot snapshot =
        new KyuubiRestClient.Snapshot("engine-1", "spark-engine", "", 1, 3, 4, 2, 1);
    when(rest.monitor(firstId.toString()))
        .thenReturn(snapshot)
        .thenThrow(new IllegalStateException("temporary monitoring error"));
    assertEquals(2, service.monitor(first.id()).executorPoolActiveCount());
    KyuubiSessionMonitor cached = service.monitor(first.id());
    assertEquals("engine-1", cached.engineId());
    assertEquals(2, cached.executorPoolActiveCount());
    assertEquals("temporary monitoring error", cached.monitoringError());

    ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
    verify(driver, times(2)).connect(urls.capture(), any());
    assertNotEquals(urls.getAllValues().get(0), urls.getAllValues().get(1));
    urls
        .getAllValues()
        .forEach(
            url ->
                assertTrue(
                    url.contains(
                        "spark.driver.extraJavaOptions=-Dderby.system.home=/tmp/kudos-metastore-")));
  }

  private static KyuubiSessionInfo awaitReady(KyuubiService service, String id) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      KyuubiSessionInfo session =
          service.sessions().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
      if ("READY".equals(session.state())) {
        return session;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Session did not become ready");
  }

  private static HiveConnection connection(UUID sessionId) throws Exception {
    HiveConnection connection = mock(HiveConnection.class);
    Statement statement = mock(Statement.class);
    ResultSet result = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery("SELECT 1")).thenReturn(result);

    ByteBuffer guid =
        ByteBuffer.allocate(16)
            .putLong(sessionId.getMostSignificantBits())
            .putLong(sessionId.getLeastSignificantBits())
            .flip();
    TSessionHandle handle =
        new TSessionHandle(new THandleIdentifier(guid, ByteBuffer.allocate(16)));
    Field field = HiveConnection.class.getDeclaredField("sessHandle");
    field.setAccessible(true);
    field.set(connection, handle);
    return connection;
  }
}
