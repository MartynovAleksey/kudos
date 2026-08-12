# kudos

`kudos` is a lightweight stateless Spring Boot API for HDFS, Kyuubi/Spark SQL, HBase, and Apache Ozone on behalf of an LDAP-authenticated user.

The repository also contains a fully containerized single-node test environment with FreeIPA and Kerberos. By default, the environment starts the official Hue image from Docker Hub on port `8082` as a reference environment; its File Browser is connected to the same Kerberos-enabled HDFS as KUDOS.

> **Important:** the usernames and passwords below are public demonstration credentials for this repository. They are intended only for the isolated local test environment and must not be used in production.

## Feature status

The application provides:

- LDAP authentication through HTTP Basic;
- Spark SQL execution through Kerberized Kyuubi over Hive JDBC;
- HDFS browsing through WebHDFS with SPNEGO;
- HBase access through the REST Gateway with SPNEGO; the application does not open ZooKeeper or HBase RPC;
- Apache Ozone browsing through `ofs://` and Kerberos;
- reference Hue from Docker Hub with a configured Kerberos HDFS File Browser.

The Java UI distinguishes two LDAP roles: `administrator` and `user`. The administrator can see all Spark jobs and the Spark UI; the user can see only applications whose `sparkUser` matches their login and cannot open a shared or another user's `/spark-ui/**` by direct URL. SQL, HDFS, Ozone, and HBase operations run under the user's Kerberos identity, while the corresponding cluster service makes the final data-access decision.

## Three project parts

The project is divided into three independent parts:

| Part | Where | Documentation |
|-------|-----|--------------|
| **Test environment** — single-node environment (FreeIPA, HDFS, Kyuubi, HBase, Ozone, Vault) and running the application against it | Docker Compose (`compose.yaml`) | this README (below) |
| **Image build** — multi-stage build with optional security scans | Docker (`docker/app/Dockerfile`) | [docs/BUILD.md](BUILD.md) |
| **Production deployment** of the ready application outside test mode | Helm (`deploy/helm/kudos`) | [docs/DEPLOY-HELM.md](DEPLOY-HELM.md) |

Design documents for planned new components (SQL in Trino and StarRocks,
and Flink applications through a proxy) are in [docs/design/](design/README.md).

In production mode, HashiCorp Vault issues the TLS certificate (PKI + AppRole), while configuration and secrets come from external ConfigMaps/Secrets. The same contract (Vault issues the certificate and the Vault Agent sidecar places it in `/vault/secrets`) is reproduced in the test environment through dev-mode Vault.

## Quick start of the test environment




- current Docker Desktop with Docker Compose v2;

- about 20 GB of free disk space for images, build cache, and named volumes is recommended;
- the ports listed in the table below must be available.

Java and Maven on the host are not required to start only the Docker environment. Running `mvn test` locally requires JDK 21 and Maven 3.9+.




./dev/scripts/verify-no-hbase-client.sh

```bash
./scripts/verify-no-hbase-client.sh
```

On Apple Silicon, Docker Desktop must be able to run `linux/amd64` images. Emulation support is usually enabled by default. Kyuubi, Hadoop, Ozone, and the official Hue run as `linux/amd64`; FreeIPA, HBase, and the Java application are built for the host architecture.



cd kudos

```bash
cd kudos
chmod +x scripts/*.sh
./scripts/run-test-env.sh -d
```

2. detects the Mac architecture;

4. clears `DOCKER_DEFAULT_PLATFORM` for this run only;
5. uses a local Docker configuration without the macOS credential helper;
6. runs `docker compose up --build --remove-orphans`.

The first start downloads base images and Maven dependencies and can take 10–30 minutes. The `Downloading Maven dependencies` step prints the full Maven progress and is not a hang. Initializing a new FreeIPA realm usually takes another 3–5 minutes.




docker compose ps

```bash
docker compose ps
```

The main services should reach the `healthy` state, while `hue-reference` should be `running`. The Java application is created only after successful FreeIPA, Kyuubi, HDFS, HBase, and Ozone health checks.

curl --fail --silent --resolve app.test.local:8443:127.0.0.1 \

```bash
curl --fail --silent --resolve app.test.local:8443:127.0.0.1 \
  --cacert <(docker compose exec -T freeipa cat /shared/ca.crt) \
  https://app.test.local:8443/actuator/health
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

{"status":"UP"}

```json
{"status":"UP"}
```

### Reference Hue and HDFS

Open `http://localhost:8082/`: reference Hue uses `docker/hue-reference/hue.ini`, HDFS `core-site.xml`/`hdfs-site.xml`, and a separate `hue-secrets` volume. It contains only the test-only `admin.keytab`, CA, and `krb5.conf`, not service keytabs. To check UI availability and Kerberos HDFS listing from the Hue container:

```bash
./scripts/test-hue-hdfs.sh
```

This configuration is intended only for the local reference environment; its keytabs and passwords must not be transferred to production.



cd kudos

```bash
cd kudos
docker compose down --remove-orphans
./scripts/run-test-env.sh -d
```

cd kudos

```bash
cd kudos
docker compose down --remove-orphans
```



cd kudos

```bash
cd kudos
docker compose down --volumes --remove-orphans
./scripts/run-test-env.sh -d
```



docker compose logs --tail=200 freeipa

```bash
docker compose logs --tail=200 freeipa
docker compose logs --tail=200 kyuubi
docker compose logs --tail=200 hdfs
docker compose logs --tail=200 hbase
docker compose logs --tail=200 ozone
docker compose logs --tail=200 app
docker compose logs --tail=200 hue-reference
```

docker compose logs --follow --tail=100 ozone

```bash
docker compose logs --follow --tail=100 ozone
```



cd kudos

```bash
cd kudos
mvn test
```

be available only when the value is `true`.

An HTML report for the latest Surefire run is generated with:


python3 dev/scripts/render-test-report.py --output target/reports/api-toggle-autotest-report.html

```bash
python3 scripts/render-test-report.py --output target/reports/api-toggle-autotest-report.html
```



cd kudos

```bash
cd kudos
./scripts/test-kerberos-services.sh
```

- Kyuubi JDBC SQL;

- Ozone Kerberos put/get and OFS;
- Kyuubi JDBC SQL;
- WebHDFS SPNEGO create/read/delete;

- HBase REST/SPNEGO create/put/get/drop;
- Spring Boot health endpoint;
- the Docker Hub Hue reference page.

All Kerberos functional checks passed.

```text
All Kerberos functional checks passed.
```

```bash

```bash
cd kudos
./scripts/test-start-cycles.sh 10
```

`test-kerberos-services.sh`. The number of cycles can be changed with a positive





```bash
cd kudos
./scripts/hbase-loadgen.sh              # seed a representative matrix
MODE=clean ./scripts/hbase-loadgen.sh   # remove everything created by the generator
```

```bash

Flink session does not register catalogs. Therefore

The Editor has a collapsible **Catalog** panel on the left: it loads the

```bash
cd kudos
docker compose ps
```

environment, fully reset the `ozone-data` volume

paths and stale SCM state are incompatible. `run-test-env.sh` does this

```bash
docker compose exec -T \
  -e TEST_ADMIN_PASSWORD='KudosAdmin2026Secure!' \
  freeipa bash -lc '
    set -euo pipefail
    kdestroy 2>/dev/null || true
    printf "%s\n" "$TEST_ADMIN_PASSWORD" | kinit admin@TEST.LOCAL
    klist
    ldapwhoami -x \
      -H ldap://freeipa.test.local \
      -D uid=admin,cn=users,cn=accounts,dc=test,dc=local \
      -w "$TEST_ADMIN_PASSWORD"
  '
```

The gate applies to **metadata**: the KUDOS Catalog panel accesses IRC as the

visible only within the granted permissions. The demo policy is provisioned

```bash
docker compose exec -T freeipa bash -lc '
  set -euo pipefail
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  klist -s
  echo "Kerberos keytab login: OK"
'
```

`docker compose up -d gravitino`):

```bash
docker compose exec -T kyuubi bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  /opt/kyuubi/bin/beeline \
    -u "jdbc:hive2://kyuubi.test.local:10009/default;principal=kyuubi/kyuubi.test.local@TEST.LOCAL" \
    --silent=true \
    --showHeader=false \
    --outputformat=csv2 \
    -e "SELECT 40 + 2 AS result"
'
```

**The query path of the engines is not protected yet**: engines access IRC as

```text
42
```

Run all commands in this section from the project root with the environment running:

### 3. HDFS 3.4.2: WebHDFS SPNEGO create/read/delete

docker compose ps

```bash
docker compose exec -T hdfs bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  printf "hdfs-webhdfs-spnego-ok\n" >/tmp/kudos-hdfs-in
  base=http://hdfs.test.local:9870/webhdfs/v1

  curl --fail --silent --negotiate -u : \
    -X PUT "$base/user/admin?op=MKDIRS"

  curl --fail --silent --location-trusted --negotiate -u : \
    -X PUT \
    --upload-file /tmp/kudos-hdfs-in \
    "$base/user/admin/kudos-manual-test?op=CREATE&overwrite=true"

  curl --fail --silent --location-trusted --negotiate -u : \
    "$base/user/admin/kudos-manual-test?op=OPEN"

  curl --fail --silent --negotiate -u : \
    -X DELETE \
    "$base/user/admin/kudos-manual-test?op=DELETE"
'
```

  kdestroy 2>/dev/null || true

```text
hdfs-webhdfs-spnego-ok
```

### 4. Apache Ozone 2.0.0: Kerberos Object Store put/get

```bash
docker compose exec -T ozone bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export OZONE_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  export OZONE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  ozone sh volume create /kudosmanual --user=admin 2>/dev/null || true
  ozone sh bucket create /kudosmanual/files 2>/dev/null || true

  printf "ozone-object-store-ok\n" >/tmp/kudos-ozone-in
  rm -f /tmp/kudos-ozone-out
  ozone sh key put \
    /kudosmanual/files/manual-key \
    /tmp/kudos-ozone-in
  ozone sh key get \
    /kudosmanual/files/manual-key \
    /tmp/kudos-ozone-out
  cmp /tmp/kudos-ozone-in /tmp/kudos-ozone-out
  cat /tmp/kudos-ozone-out
  ozone sh key delete /kudosmanual/files/manual-key
'
```

```bash

  set -euo pipefail

  kdestroy 2>/dev/null || true

```bash
docker compose exec -T ozone bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export OZONE_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  export OZONE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  ozone sh volume create /kudosmanual --user=admin 2>/dev/null || true
  ozone sh bucket create /kudosmanual/files 2>/dev/null || true

  printf "ozone-ofs-ok\n" >/tmp/kudos-ofs-in
  ozone fs -fs ofs://ozone.test.local/ \
    -put -f /tmp/kudos-ofs-in /kudosmanual/files/manual-ofs-key
  ozone fs -fs ofs://ozone.test.local/ \
    -cat /kudosmanual/files/manual-ofs-key
  ozone fs -fs ofs://ozone.test.local/ \
    -rm -skipTrash /kudosmanual/files/manual-ofs-key
'
```

hdfs-webhdfs-spnego-ok

```text
ozone-ofs-ok
```

docker compose exec -T ozone bash -lc '

  export KRB5_CONFIG=/shared/krb5.conf

  export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop

```bash
docker compose exec -T hbase bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  base=http://hbase.test.local:8080 table=kudos_manual
  curl --silent --negotiate -u : -X DELETE "$base/$table/schema" >/dev/null 2>&1 || true
  curl --fail --silent --negotiate -u : -H "Content-Type: application/json" \
    -X PUT "$base/$table/schema" \
    -d "{\"name\":\"$table\",\"ColumnSchema\":[{\"name\":\"d\"}]}" >/dev/null
  curl --fail --silent --negotiate -u : -H "Content-Type: application/json" \
    -X PUT "$base/$table/row1" \
    -d "{\"Row\":[{\"key\":\"cm93MQ==\",\"Cell\":[{\"column\":\"ZDp2YWx1ZQ==\",\"\$\":\"aGJhc2UtcmVzdC1rZXJiZXJvcy1vaw==\"}]}]}"
  curl --fail --silent --negotiate -u : "$base/$table/row1"
  curl --fail --silent --negotiate -u : -X DELETE "$base/$table/schema" >/dev/null
'
```

```

```text
aGJhc2UtcmVzdC1rZXJiZXJvcy1vaw==
```

The following check uses the Hadoop-compatible `ofs://` interface, the same interface used by the Java application's `OzoneService`:

```bash

```bash
docker compose exec -T freeipa cat /shared/ca.crt > /tmp/kudos-ca.crt
resolve=(--resolve app.test.local:8443:127.0.0.1 --cacert /tmp/kudos-ca.crt)
```

  export OZONE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"

```bash
curl --fail --silent "${resolve[@]}" https://app.test.local:8443/actuator/health
```



```bash
curl --fail --silent "${resolve[@]}" \
  -u 'admin:KudosAdmin2026Secure!' \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT 40 + 2 AS result"}' \
  https://app.test.local:8443/api/sql
```



```bash
curl --fail --silent --get "${resolve[@]}" \
  -u 'admin:KudosAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  https://app.test.local:8443/api/hdfs
```

### 6. HBase 2.6.2: REST Gateway and SPNEGO put/get

```bash
curl --fail --silent "${resolve[@]}" \
  -u 'admin:KudosAdmin2026Secure!' \
  https://app.test.local:8443/api/hbase/tables
```

  kdestroy 2>/dev/null || true

```bash
curl --fail --silent --get "${resolve[@]}" \
  -u 'admin:KudosAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  https://app.test.local:8443/api/ozone
```

    -d "{\"Row\":[{\"key\":\"cm93MQ==\",\"Cell\":[{\"column\":\"ZDp2YWx1ZQ==\",\"\$\":\"aGJhc2UtcmVzdC1rZXJiZXJvcy1vaw==\"}]}]}"

### 8. Official Hue from Docker Hub

Reference Hue does not replace the Java application and does not participate in functional storage tests. It is provided for visual and behavioral comparison with the future UI implementation.

Open in a browser:

```text
http://localhost:8082/
```

Or check HTTP from the terminal:

```bash
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

Compose builds a thin image layer from `gethue/hue:latest`. The only change is replacing the Python `polars` wheel with the official `polars-lts-cpu` wheel of the same version, because the regular x86_64 wheel exits with `SIGILL` under Rosetta on Apple Silicon. Hue code, Mako templates, CSS, and JavaScript are not modified.

  curl --fail --silent --negotiate -u : -X DELETE "$base/$table/schema" >/dev/null

```

- Java 21;

```text
- Spring Security LDAP;
- Hadoop client 3.4.2;

- Hive JDBC standalone 4.0.1;

The application is available only over HTTPS at `app.test.local:8443`. Save the environment CA and add it to every request with `--resolve`/`--cacert`:

```bash

resolve=(--resolve app.test.local:8443:127.0.0.1 --cacert /tmp/kudos-ca.crt)




```bash
curl --fail --silent "${resolve[@]}" https://app.test.local:8443/actuator/health
```

6. Calls to HDFS, Kyuubi, HBase, or Ozone run on behalf of this user; each service applies its own authorization.


curl --fail --silent "${resolve[@]}" \

  -H 'Content-Type: application/json' \

  https://app.test.local:8443/api/sql




```bash
curl --fail --silent --get "${resolve[@]}" \
  -u 'admin:KudosAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  https://app.test.local:8443/api/hdfs
```

List HBase tables:

```bash

  -u 'admin:KudosAdmin2026Secure!' \
  https://app.test.local:8443/api/hbase/tables
```

View Ozone OFS:

```bash

  -u 'admin:KudosAdmin2026Secure!' \

  https://app.test.local:8443/api/ozone


For each protected request, Spring Security performs an LDAP bind, then obtains the user's TGT with the same password and executes the client call in `UGI.doAs` under that ticket. The application keytab is not used.

## Java Application Architecture

### HTTPS

The application runs only over HTTPS on port `8443`. The certificate for `app.test.local` is issued by **Vault PKI**: dev-mode Vault starts in Compose, `docker/vault/bootstrap.sh` configures the PKI engine and AppRole, and the `vault-agent` sidecar issues the certificate through AppRole and places it as PEM in the shared volume (`/vault/secrets/tls.crt`, `tls.key`, `ca.crt`). The application reads it through the Spring Boot SSL bundle under the `vault` profile (`application-vault.yml`), while `reload-on-update` picks up rotation without a restart. Clients verify the certificate against the Vault CA. From the terminal:

```bash
docker compose exec -T vault-agent cat /vault/secrets/ca.crt > /tmp/kudos-ca.crt
curl --fail --silent \
  --resolve app.test.local:8443:127.0.0.1 \
  --cacert /tmp/kudos-ca.crt \
  https://app.test.local:8443/actuator/health
```

The application is stateless: user sessions, Kerberos tickets, and query results are not stored in a local database.

### Request Flow and Security Model

The application does not store its own Kerberos credentials. At login, it obtains the user's personal ticket-granting ticket from the user's password—the same exchange performed by `kinit`—and makes all service calls on behalf of that ticket. The services themselves perform authorization based on the identity in the ticket, so the application has no separate authorization layer and should not have one.

1. The client provides LDAP credentials through HTTP Basic or the UI login form.

3. Using the same password, `KerberosTicketService` obtains the user's TGT through `Krb5LoginModule`. If the KDC rejects the request, login fails even when the LDAP bind succeeds: a session without a ticket could not access any service anyway.

5. `KerberosExecutor` takes the `Subject` from the session and wraps the call in `UserGroupInformation.getUGIFromSubject(subject).doAs(...)`.

7. The session lifetime is limited by the ticket lifetime: after it expires, `TicketExpiryFilter` terminates the session, and `KerberosTicketCleanup` destroys the ticket on logout. See the “Session lifetime” section for details.
| --- | --- |
| `KudosUiApplication.java` | Spring Boot entrypoint. |

Only `krb5.conf` (the `app-secrets` volume) and Vault-issued TLS material (the `app-tls` volume) are mounted into the application container; the cluster service keytabs are never mounted there. Even if the application process is compromised, it has no keytab that could be used to authenticate as a service.
| `WebConfig.java` | Serves vendored Hue styles and fonts together with custom UI assets. |
### Obtaining and storing the Kerberos ticket

**Obtaining the ticket.** The application has no own keytab and obtains the ticket from the user's password—the same exchange with the KDC performed by `kinit`. The logic is in [`KerberosTicketService`](../src/main/java/com/kudos/ui/security/KerberosTicketService.java):

1. A principal is formed from the `kudos.cluster.kerberos-principal` template (`{user}` → LDAP login), for example `admin@TEST.LOCAL`.
2. An empty `javax.security.auth.Subject` and a `LoginContext` named `kudos` are created over the standard `com.sun.security.auth.module.Krb5LoginModule`. The module is configured programmatically (not through `jaas.conf`) with the following options:
   - `useKeyTab=false`, `storeKey=false` — no keytab is used and keys are not stored;
   - `useTicketCache=false` — the local ticket cache (`/tmp/krb5cc_*`, `KRB5CCNAME`) is neither read nor written: the only login input is the supplied password;
4. `context.login()` sends an AS-REQ to the KDC (the address and realm come from `krb5.conf`, whose path is set by `-Djava.security.krb5.conf=/run/secrets-kudos/krb5.conf`). On success, the TGT is placed in the `Subject` as a private credential of type `KerberosTicket`; if the KDC rejects the request, `LoginException` is thrown and application login fails.
5. `getEndTime()` is read from the TGT as its expiration time; it is returned together with the `Subject` in the `IssuedTicket` record.

**Storage.** The ticket lives only in the process memory and only for the duration of the user's session:


### HTTP API

- The password is not stored: `KerberosAuthentication.getCredentials()` returns `null`. Only the ticket derived from it is stored.
| --- | --- | --- |
- Usage: [`KerberosExecutor`](../src/main/java/com/kudos/ui/service/KerberosExecutor.java) takes the `Subject` from the `SecurityContext` and accesses the service through `UserGroupInformation.getUGIFromSubject(subject).doAs(...)`.
| `POST /api/sql` | Kyuubi SQL, rows as objects | JSON `{"sql":"SELECT 1"}`. |
| `POST /api/sql/execute` | Kyuubi SQL, columns and rows separately | JSON `{"sql":"SELECT 1"}`, up to 1,000 rows. |
### Session lifetime

The session must not outlive the ticket: after expiration, all services reject the ticket, and the authenticated user could not do anything anyway. Therefore:

- At login, `KerberosTicketService` reads the TGT expiration time (`KerberosTicket.getEndTime()`) and places it in `KerberosAuthentication`.
- `TicketExpiryFilter` checks this time on every request: as soon as the ticket expires, it destroys the ticket, terminates the session, and clears the context. It redirects pages to `/login?expired` and rejects `/api` and `/ui-api` calls with `401` without `WWW-Authenticate`.
- A countdown to ticket expiration appears in the bottom-right corner of every screen. It is seeded with the number of seconds calculated on the server, so it does not depend on clock skew in the browser; it turns amber five minutes before expiration and logs out automatically when it reaches zero.
- Logout (`GET /logout`—the “Sign out” link in the sidebar and timer-triggered logout) destroys the ticket through `KerberosTicketCleanup` and invalidates the session.

### HTTPS

The application runs only over HTTPS on port `8443`. The certificate for `app.test.local` is issued by **Vault PKI**: dev-mode Vault starts in Compose, `dev/docker/vault/bootstrap.sh` configures the PKI engine and AppRole, and the `vault-agent` sidecar issues the certificate through AppRole and places it as PEM in the shared volume (`/vault/secrets/tls.crt`, `tls.key`, `ca.crt`). The application reads it through the Spring Boot SSL bundle under the `vault` profile (`application-vault.yml`), and `reload-on-update` picks up rotation without a restart. Clients verify the certificate against the Vault CA. From the terminal:

```bash
docker compose exec -T vault-agent cat /vault/secrets/ca.crt > /tmp/kudos-ca.crt
curl --fail --silent \
  --resolve app.test.local:8443:127.0.0.1 \
  --cacert /tmp/kudos-ca.crt \
  https://app.test.local:8443/actuator/health
```




The application serves screens at `https://app.test.local:8443/` that reproduce the forms of reference Hue 4:


| --- | --- | --- |

Every completed request to `/api/**` and `/ui-api/**` is recorded as an audit event (JSON: time, user, method, effective path, and HTTP status). Events are always written to a separate rolling file `${kudos.logging.dir}/audit.log` (`AuditInterceptor` → `AuditService` → the `audit` logger). Events can also be published to Kafka: set `kudos.audit.kafka-enabled: true`, `kudos.audit.kafka-topic`, and `spring.kafka.bootstrap-servers`. Kafka is disabled by default, so the test environment does not require a broker. A Kafka publishing failure does not affect the request itself.

### Main classes

| File | Purpose |
| --- | --- |

| `LdapSecurityConfig.java` | LDAP bind, TGT acquisition at login, separate chains for `/api` (Basic), `/ui-api` (session + CSRF), and UI (form), logout, and session termination when the ticket expires. |

| `WebConfig.java` | Serves vendored web fonts (Font Awesome, Roboto) and custom UI assets. |

| `AppConfig.java` | Connects `ClusterProperties` to the Spring context. |

| `KerberosExecutor.java` | Executes calls under the user's session ticket through `UGI.doAs`. |

| `KerberosTicketCleanup.java` | Destroys the user's ticket on logout or expiration. |

| `KyuubiService.java` | JDBC connection, SQL execution, and conversion of `ResultSet` to JSON rows. |

| `TrinoService.java` | Sessionless SQL execution in Trino using the authenticated user's Kerberos ticket. |

The shell is built from vendored Hue styles (`hue.css`, `cui.css`, `bootstrap2.css`, `login.css`, Font Awesome, and Roboto) in `src/main/resources/hue-upstream/desktop/static`. The Hue 4 left sidebar is styled inside its JavaScript bundle, which is not vendored, so its dimensions and colors are reproduced in `src/main/resources/app-static/kudos.css` from values taken from the reference container. Hue logos and trademarks are not reproduced.

| `OzoneService.java` | Hadoop `FileSystem` for `ofs://` URIs, listing, and object reads. |

| `UiController.java` | UI pages: Editor, Files, Ozone, HBase, and the login form. |

### HTTP API

```bash
cd kudos
docker compose restart app
```

| `GET /api/hdfs` | Raw WebHDFS `LISTSTATUS` | Query parameter `path`, `/` by default. |
| --- | --- |
| `GET /api/hdfs/preview` | First 64 KB of a file | Query parameter `path`. |
| `GET /api/hbase/tables` | Table list | No parameters. |
| `GET /api/hbase/describe` | Table column families and their properties | Query parameter `table`. |
| `GET /api/hbase/regions` | Table regions and key boundaries | Query parameter `table`. |
| `GET /api/hbase/autocomplete` | Row keys by prefix | `table`, `prefix`, `limit`. |
| `GET /api/hbase/row` | One row | `table`, `row`, `columns`. |
| `GET /api/hbase/cell/versions` | Cell version history | `table`, `row`, `column`, `versions`. |
| `POST /api/hbase/table/create` | Create a table | JSON `{table, families:[…]}`. |
| `POST /api/hbase/table/delete` | Delete a table | JSON `{table}`. |

| `POST /api/hbase/row` | Write or update row cells | JSON `{table, row, cells}`. |

| `POST /api/hbase/cell/delete` | Delete cells | JSON `{table, row, columns}`. |

| `POST /api/hbase/bulk` | Bulk upload from CSV | Multipart `file` + query `table`. |

```yaml
kudos:
  api:
    enabled: false
```

```bash
KUDOS_API_ENABLED=false docker compose up -d --no-deps app
```

| `/filebrowser` | File Browser | HDFS through WebHDFS. |

| `/hbase` | HBase Browser: tables, families, scan/search, cells and versions, mutations, bulk upload; cursor-based row pagination and table-list filtering; deleting a table requires entering a confirmation word. | HBase through the native REST Gateway and SPNEGO. |

```bash
curl --silent --output /dev/null --write-out '%{http_code}\n' \
  "${resolve[@]}" https://app.test.local:8443/api/hbase/tables
curl --silent --output /dev/null --write-out '%{http_code}\n' \
  "${resolve[@]}" https://app.test.local:8443/v3/api-docs
An ordinary user receives only their own jobs from the server; the user filter field is hidden from them, and a manually supplied `user` parameter is ignored. An administrator can additionally filter the list by owner. The date range can be switched between a day, week, month, and custom period; every column can be sorted, and the page size can be selected from 10/25/50/100.
```







```text
ofs://ozone.test.local/spark/eventlogs
```

The `spark` volume and `eventlogs` bucket are created by `docker/ozone/start-ozone` at startup, and `spark-history` reads the same directory. The Java application displays the application list on `/jobs`, retrieving it from the history server REST API through SPNEGO.





The application image is built with the multi-stage `docker/app/Dockerfile` (`runtime` stage),
```bash
cd kudos
[docs/BUILD.md](BUILD.md).



| --- | --- |

| `kudos.cluster.webhdfs-url` | WebHDFS endpoint for File Browser. |
| --- | --- | --- | --- |
| `kudos.cluster.kyuubi-rest-url` | Internal Kyuubi REST endpoint for session and executor-pool state; the backend collects operations and logs for the current JDBC query. |
| `kudos.cluster.starrocks-url` | StarRocks MariaDB JDBC URL. In the test environment, Vault Agent creates `/vault/secrets/starrocks.properties`; the password is not included in YAML or the image. |
| `kudos.cluster.hbase-rest-url` | URL of the Kerberos-protected HBase REST Gateway. Environment variable: `HBASE_REST_URL`. |
| `kudos.cluster.ozone-ofs-uri` | `ofs://` root for the Ozone Browser. |
| `kudos.cluster.ozone-conf-dir` | Directory containing Ozone `core-site.xml` and `ozone-site.xml`. |
| `kudos.cluster.spark-history-url` | Spark History Server for the Jobs screen. |

| `hue-reference` | `gethue/hue:latest` | `8082` | Official Docker Hub Hue with a File Browser connected to test HDFS. |

HDFS uses replication factor `1`. Ozone is also configured for single-node replication. HBase stores data in the local filesystem inside a named volume. This is a compact functional environment, not a production deployment.



Compose dependency graph:

kudos:
  api:
    enabled: false
```
5. `app` starts only after all five infrastructure services are healthy;
6. `hue-reference` waits for healthy FreeIPA and HDFS, receives a separate test-only volume with the `admin` keytab, and starts by default.

```

The flag is read only when the process starts. In disabled mode, `/api/**`, `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, and `/v3/api-docs/**` return `404` before authentication and without `WWW-Authenticate`. Login, pages, static assets, `/ui-api/**`, the Spark UI proxy, and `/actuator/health` continue to work. `/ui-api/**` is not published in OpenAPI and is available only to an existing form-login session; unsafe requests are protected by Spring CSRF.

Mode check:

```text
TEST.LOCAL
```

  "${resolve[@]}" https://app.test.local:8443/v3/api-docs

```
| --- | --- | --- |
| LDAP/Kerberos administrator | `admin` / `admin@TEST.LOCAL` | `KudosAdmin2026Secure!` |
| LDAP/Kerberos user | `analyst` / `analyst@TEST.LOCAL` | `KudosAnalyst2026Secure!` |
| FreeIPA Directory Manager | `cn=Directory Manager` | `DirectoryManager1` |

`admin` belongs to the `kudos-administrators` LDAP group and receives the `administrator` role; `analyst` receives the `user` role. All these passwords are exposed in `compose.yaml` and the systemd bootstrap unit and are suitable only for the isolated local environment.

ofs://ozone.test.local/spark/eventlogs


| --- | --- | --- |

| `kyuubi/kyuubi.test.local@TEST.LOCAL` | `kyuubi.keytab` | Kyuubi server. |
| `nn/hdfs.test.local@TEST.LOCAL` | `nn-hdfs.test.local.keytab` | HDFS NameNode. |
| `dn/hdfs.test.local@TEST.LOCAL` | `dn-hdfs.test.local.keytab` | HDFS DataNode. |
| `HTTP/hdfs.test.local@TEST.LOCAL` | `HTTP-hdfs.test.local.keytab` | WebHDFS SPNEGO. |
| `om/ozone.test.local@TEST.LOCAL` | `om-ozone.test.local.keytab` | Ozone Manager. |
| `scm/ozone.test.local@TEST.LOCAL` | `scm-ozone.test.local.keytab` | Storage Container Manager. |
| `dn/ozone.test.local@TEST.LOCAL` | `dn-ozone.test.local.keytab` | Ozone DataNode. |
| `HTTP/ozone.test.local@TEST.LOCAL` | `HTTP-ozone.test.local.keytab` | Ozone HTTP service identity. |

| `HTTP/hbase.test.local@TEST.LOCAL` | `HTTP-hbase.test.local.keytab` | SPNEGO service identity HBase REST Gateway. |

### Containers and ports

| Compose service | Version or base image | Host ports | Role |

### Named volumes

| `hdfs` | `apache/hadoop:3.4.2` | `9870` | NameNode, DataNode, and SPNEGO WebHDFS. |
| --- | --- |
| `ozone` | `apache/ozone:2.0.0` | `9862`, `9874` | SCM, OM, DataNode, Object Store, and OFS. |
| `spark-history` | `apache/spark:4.1.0` | `18080` | Spark History Server, reading event logs from Ozone. |
| `starrocks` | `starrocks/allin1-ubuntu:3.5.20` | `9030` | Single-node FE+BE, MySQL wire protocol for the Query Editor. |
| `app` | Java 21 / Spring Boot | `8443` | kudos integration API and UI, HTTPS only. |
| `hue-secrets` | Only `admin.keytab`, `krb5.conf`, and the CA for reference Hue. |

HDFS uses replication factor `1`. Ozone is also configured for single-node replication. HBase stores data in the local filesystem inside a named volume. StarRocks uses a shared service account, `kudos_svc`: this is a test-environment limitation, while per-user permissions require a separate StarRocks LDAP integration. This is a compact functional environment, not a production deployment.






```text
src/main/java/com/kudos/ui/     Java application code
src/main/resources/              Spring configuration and vendored UI resources
src/test/                        Spring tests
docker/app/                      Java multi-stage image
docker/freeipa/                  FreeIPA image and identity bootstrap
docker/kyuubi/                   Kerberos Kyuubi/Spark configuration
docker/hdfs/                     Kerberos HDFS 3.4.2 configuration
docker/hbase/                    Kerberos HBase 2.6.2 configuration
docker/ozone/                    Kerberos Ozone 2.0.0 configuration
docker/hue-reference/            Thin compatibility layer over Docker Hub Hue
scripts/run-test-env.sh          Cross-platform environment launcher
scripts/test-kerberos-services.sh Functional Kerberos data-path tests
scripts/test-hue-hdfs.sh         Check the reference Hue File Browser and HDFS listing
scripts/test-start-cycles.sh      Repeated restart and regression tests
scripts/hbase-loadgen.sh          Seed HBase at scale to exercise the browser UI
compose.yaml                     Services, volumes, ports and dependencies
```

| Purpose | Identity | Password |

| LDAP/Kerberos administrator | `admin` / `admin@TEST.LOCAL` | `KudosAdmin2026Secure!` |

| LDAP/Kerberos security officer | `security-officer` / `security-officer@TEST.LOCAL` | `KudosSecurityOfficer2026Secure!` |

```text
image ... was found but its platform (linux/arm64) does not match the specified platform (linux/amd64)
```



| --- | --- | --- |

```bash
echo "${DOCKER_DEFAULT_PLATFORM-<not set>}"
```

### Docker Desktop Keychain `-67674`

If the Docker CLI cannot access the macOS credential helper, a public image pull may fail with a Keychain error. Project scripts use `docker/.docker-config` without a credential store only for public pulls/builds and do not modify the user's Docker Desktop login.

| `hbase/hbase.test.local@TEST.LOCAL` | `hbase-hbase.test.local.keytab` | HMaster, RegionServer, and REST Gateway as the HBase service user. |

```bash
./scripts/run-test-env.sh -d
```



### Official Hue on Apple Silicon

Docker Hub Hue is distributed as an amd64 image. Under Rosetta, the regular `polars` wheel uses an unsupported CPU instruction and may exit with `SIGILL`. `docker/hue-reference/Dockerfile` replaces only that wheel with `polars-lts-cpu`; the Hue UI remains upstream.



| --- | --- |
| `freeipa-data` | FreeIPA realm, LDAP database, KDC, and PKI state. |
| `freeipa-run` | Writable `/run` for systemd and 389 Directory Server. |
| `freeipa-tmp` | Writable FreeIPA `/tmp` with the required filesystem semantics. |
| `kerberos-shared` | `krb5.conf`, user keytab, and service keytabs. |
| `hdfs-data` | NameNode metadata and DataNode blocks. |
- `gethue/hue:latest` is intentionally used only as a moving reference; production builds must pin the digest.
| `hbase-data` | HBase and embedded ZooKeeper state. |
| `ozone-data` | SCM, OM, Ratis, and DataNode state. |



src/main/java/com/kudos/ui/     Java application code

```bash
cd kudos
mvn test
./scripts/run-test-env.sh -d
./scripts/test-kerberos-services.sh
./scripts/test-hue-hdfs.sh
./scripts/test-start-cycles.sh 10
```

dev/scripts/test-kerberos-services.sh Functional Kerberos data-path tests
