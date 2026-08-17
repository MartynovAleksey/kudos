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

// Container healthcheck for the distroless runtime image, which has no shell and
// no curl/wget — only the JRE. Run as a single-file program: `java HealthCheck.java`.
// Hits the local actuator health endpoint over HTTPS and exits 0 only when the
// status is UP. Trusts any server cert on purpose: this is a loopback liveness
// probe against our own port, not a security boundary (the real cert is validated
// by clients, and Kubernetes uses its own httpGet probe instead of this file).
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class HealthCheck {
  public static void main(String[] args) throws Exception {
    // HTTPS is off by default, so probe plain HTTP. When TLS is enabled the
    // deployment overrides HEALTHCHECK_URL with an https:// app.test.local URL —
    // that hostname matches the Vault cert's SAN, which matters because
    // java.net.http.HttpClient always verifies the hostname (and ignores attempts
    // to disable it); the self-signed CA is handled by the trust manager below.
    String url = System.getenv().getOrDefault("HEALTHCHECK_URL",
        "http://app.test.local:8443/actuator/health");

    TrustManager[] trustAll = {
      new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      }
    };
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, trustAll, new java.security.SecureRandom());

    HttpClient client = HttpClient.newBuilder()
        .sslContext(ssl)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    boolean up = response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
    System.exit(up ? 0 : 1);
  }
}
