# k8spark-ui

`k8spark-ui` is a lightweight stateless Spring Boot API for working with HDFS, Kyuubi/Spark SQL, HBase, and Apache Ozone on behalf of an LDAP-authenticated user.

The repository also contains a fully containerized single-node test environment with FreeIPA and Kerberos. By default, the environment starts the official Hue image from Docker Hub on port `8082` as a visual and behavioral reference. Hue built from source is temporarily disabled and available only through the `hue` profile.

> **Important:** the `admin` login and `K8SparkAdmin2026Secure!` password are public demonstration credentials for this repository. They are stronger than the standard test password and must not be used in production.

## Feature status

The application provides:

- LDAP authentication through HTTP Basic;
- Spark SQL execution through Kerberized Kyuubi over Hive JDBC;
- HDFS browsing through WebHDFS with SPNEGO;
- HBase access through the native `hbase-client` and Kerberos RPC;
- Apache Ozone browsing through `ofs://` and Kerberos;
- a separate unmodified Hue UI from Docker Hub for interface comparison.

Role-based and object-level authorization are not yet implemented in the Java application. All APIs except the health endpoint require successful LDAP authentication, and requests to cluster services use the same user's Kerberos identity.

## Quick start of the test environment

### Requirements

- macOS on Intel or Apple Silicon M1/M2/M3/M4;
- current Docker Desktop with Docker Compose v2;
- at least 12 GB of memory available to Docker Desktop is recommended;
- about 20 GB of free disk space for images, build cache, and named volumes is recommended;
- the ports listed in the table below must be available.

Java and Maven are not required on the host to run only the Docker environment. Local `mvn test` requires JDK 21 and Maven 3.9+.

On Apple Silicon, Docker Desktop must be able to run `linux/amd64` images. Emulation support is usually enabled by default. Kyuubi, Hadoop, Ozone, and the official Hue image run as `linux/amd64`; FreeIPA, HBase, and the Java application are built for the host architecture.

### First launch

Run from Terminal:

```bash
cd k8spark-ui
chmod +x scripts/*.sh
./scripts/run-test-env.sh -d
```

The script:

1. checks that Docker Desktop is available;
2. detects the Mac architecture;
3. removes only obsolete local `freeipa` and `app` images built for another architecture;
4. resets `DOCKER_DEFAULT_PLATFORM` only for this launch;
5. uses a local Docker configuration without the macOS credential helper;
6. runs `docker compose up --build --remove-orphans`.

The first launch downloads base images and Maven dependencies and may take 10–30 minutes. The `Downloading Maven dependencies` stage shows full Maven progress and is not a hang. Initializing a new FreeIPA realm usually takes another 3–5 minutes.

Check the container status:

```bash
docker compose ps
```

The main services should become `healthy`, while `hue-reference` should be `running`. The Java application is created only after successful health checks for FreeIPA, Kyuubi, HDFS, HBase, and Ozone.

Check the available HTTP endpoints:

```bash
curl --fail --silent http://localhost:8081/actuator/health
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

Expected health response:

```json
{"status":"UP"}
```

### Regular restart

Named volumes preserve the realm, keytabs, and service data:

```bash
cd k8spark-ui
docker compose down --remove-orphans
./scripts/run-test-env.sh -d
```

### Stop

```bash
cd k8spark-ui
docker compose down --remove-orphans
```

### Full test environment reset

The following command permanently removes the test FreeIPA realm, keytabs, and HDFS, HBase, and Ozone data:

```bash
cd k8spark-ui
docker compose down --volumes --remove-orphans
./scripts/run-test-env.sh -d
```

Use a full reset after an incompatible change to the FreeIPA bootstrap or service data format. Volumes do not need to be removed for a regular restart.

### Startup logs and diagnostics

```bash
docker compose logs --tail=200 freeipa
docker compose logs --tail=200 kyuubi
docker compose logs --tail=200 hdfs
docker compose logs --tail=200 hbase
docker compose logs --tail=200 ozone
docker compose logs --tail=200 app
docker compose logs --tail=200 hue-reference
```

For continuous output, add `--follow`, for example:

```bash
docker compose logs --follow --tail=100 ozone
```

## Automated testing

### Unit and Spring context tests

```bash
cd k8spark-ui
mvn test
```

### Full Kerberos functional test

After starting the containers, run:

```bash
cd k8spark-ui
./scripts/test-kerberos-services.sh
```

The script checks not only TCP ports but also real data paths as `admin@TEST.LOCAL`:

- LDAP bind and obtaining a Kerberos TGT with a password;
- Kyuubi JDBC SQL;
- WebHDFS SPNEGO create/read/delete;
- Ozone Kerberos put/get and OFS;
- HBase Kerberos RPC create/put/get/drop;
- the Spring Boot health endpoint;
- the Docker Hub Hue reference page.

A successful run ends with:

```text
All Kerberos functional checks passed.
```

### Ten restart cycles

```bash
cd k8spark-ui
./scripts/test-start-cycles.sh 10
```

Each cycle preserves named volumes, stops and restarts the already-built images, waits for health checks, verifies the published ports, and runs the complete `test-kerberos-services.sh`. The number of cycles can be changed with a positive integer argument.

## Manual testing of each component

Run all commands in this section from the project root while the environment is running:

```bash
cd k8spark-ui
docker compose ps
```

### 1. FreeIPA: LDAP and Kerberos

Verify obtaining a TGT with a password, the credential cache contents, and the LDAP bind:

`klist` should show the `admin@TEST.LOCAL` principal, and `ldapwhoami` should output the LDAP DN for user `admin`.

### 2. Kyuubi: JDBC SQL with Kerberos

```bash
docker compose exec -T kyuubi bash -lc 'kinit -kt /shared/admin.keytab admin@TEST.LOCAL; /opt/kyuubi/bin/beeline -u "jdbc:hive2://kyuubi.test.local:10009/default;principal=kyuubi/kyuubi.test.local@TEST.LOCAL" -e "SELECT 40 + 2 AS result"'
```

Expected result line:

```text
42
```

This is a real Hive JDBC handshake with the Kyuubi service principal, not an open-port check.

### 3. HDFS 3.4.2: WebHDFS SPNEGO create/read/delete

The check is performed through HTTP WebHDFS specifically. The `--negotiate` flag enables SPNEGO, while `--location-trusted` preserves Kerberos authentication when redirecting from the NameNode to the DataNode.

```bash
docker compose exec -T hdfs bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  printf "hdfs-webhdfs-spnego-ok\n" >/tmp/k8spark-hdfs-in
  base=http://hdfs.test.local:9870/webhdfs/v1

  curl --fail --silent --negotiate -u : \
    -X PUT "$base/user/admin?op=MKDIRS"

  curl --fail --silent --location-trusted --negotiate -u : \
    -X PUT \
    --upload-file /tmp/k8spark-hdfs-in \
    "$base/user/admin/k8spark-manual-test?op=CREATE&overwrite=true"

  curl --fail --silent --location-trusted --negotiate -u : \
    "$base/user/admin/k8spark-manual-test?op=OPEN"

  curl --fail --silent --negotiate -u : \
    -X DELETE \
    "$base/user/admin/k8spark-manual-test?op=DELETE"
'
```

The read output must contain:

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

  ozone sh volume create /k8sparkmanual --user=admin 2>/dev/null || true
  ozone sh bucket create /k8sparkmanual/files 2>/dev/null || true

  printf "ozone-object-store-ok\n" >/tmp/k8spark-ozone-in
  rm -f /tmp/k8spark-ozone-out
  ozone sh key put \
    /k8sparkmanual/files/manual-key \
    /tmp/k8spark-ozone-in
  ozone sh key get \
    /k8sparkmanual/files/manual-key \
    /tmp/k8spark-ozone-out
  cmp /tmp/k8spark-ozone-in /tmp/k8spark-ozone-out
  cat /tmp/k8spark-ozone-out
  ozone sh key delete /k8sparkmanual/files/manual-key
'
```

The expected output is `ozone-object-store-ok` and a zero exit code.

### 5. Apache Ozone: OFS put/read/delete with Kerberos

The following check uses the Hadoop-compatible `ofs://` interface, the same interface used by the Java application's `OzoneService`:

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

  ozone sh volume create /k8sparkmanual --user=admin 2>/dev/null || true
  ozone sh bucket create /k8sparkmanual/files 2>/dev/null || true

  printf "ozone-ofs-ok\n" >/tmp/k8spark-ofs-in
  ozone fs -fs ofs://ozone.test.local/ \
    -put -f /tmp/k8spark-ofs-in /k8sparkmanual/files/manual-ofs-key
  ozone fs -fs ofs://ozone.test.local/ \
    -cat /k8sparkmanual/files/manual-ofs-key
  ozone fs -fs ofs://ozone.test.local/ \
    -rm -skipTrash /k8sparkmanual/files/manual-ofs-key
'
```

Expected output:

```text
ozone-ofs-ok
```

The test environment uses a single Ozone DataNode, so the client and server configuration specifies single-node replication. This setup is intended for local tests only and is not a production topology.

### 6. HBase 2.6.2: Kerberos RPC put/get

The commands below use the native HBase RPC client. Any existing table with the same name is removed before the test, and the table is also cleaned up afterward.

```bash
docker compose exec -T hbase bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export HBASE_HOME=/opt/hbase
  export HBASE_CONF_DIR=/opt/hbase/conf
  export HBASE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  printf "disable '\''k8spark_manual'\''\ndrop '\''k8spark_manual'\''\n" \
    | /opt/hbase/bin/hbase shell -n >/dev/null 2>&1 || true

  printf "create '\''k8spark_manual'\'', '\''d'\''\nput '\''k8spark_manual'\'', '\''row1'\'', '\''d:value'\'', '\''hbase-kerberos-rpc-ok'\''\nget '\''k8spark_manual'\'', '\''row1'\''\ndisable '\''k8spark_manual'\''\ndrop '\''k8spark_manual'\''\n" \
    | /opt/hbase/bin/hbase shell -n
'
```

The `get` output must contain:

```text
value=hbase-kerberos-rpc-ok
```

### 7. Spring Boot API with LDAP and user Kerberos identity

The health endpoint is available without authentication:

```bash
curl --fail --silent http://localhost:8081/actuator/health
```

SQL through Kyuubi:

```bash
curl --fail --silent \
  -u 'admin:K8SparkAdmin2026Secure!' \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT 40 + 2 AS result"}' \
  http://localhost:8081/api/sql
```

Browse HDFS through WebHDFS:

```bash
curl --fail --silent --get \
  -u 'admin:K8SparkAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  http://localhost:8081/api/hdfs
```

List HBase tables:

```bash
curl --fail --silent \
  -u 'admin:K8SparkAdmin2026Secure!' \
  http://localhost:8081/api/hbase/tables
```

Browse Ozone OFS:

```bash
curl --fail --silent --get \
  -u 'admin:K8SparkAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  http://localhost:8081/api/ozone
```

For every protected request, Spring Security first performs an LDAP bind. The Java code then resolves `admin@TEST.LOCAL` and `/run/keytabs/admin.keytab`, creates a separate Hadoop UGI, and performs the client call inside `UGI.doAs`.

### 8. Official Hue from Docker Hub

Reference Hue does not replace the Java application and is not involved in functional storage tests. It is used for visual and behavioral comparison with the future UI implementation.

Open in a browser:

```text
http://localhost:8082/
```

Alternatively, check HTTP from the terminal:

```bash
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

Compose builds a thin image layer from `gethue/hue:latest`. The only change is replacing the `polars` Python wheel with the official `polars-lts-cpu` wheel of the same version, because the regular x86_64 wheel terminates with `SIGILL` under Rosetta on Apple Silicon. Hue code, Mako templates, CSS, and JavaScript are unchanged.

### 9. Hue from source, disabled by default

The `hue` service is excluded from the default startup by a Compose profile. To explicitly build and start it:

```bash
cd k8spark-ui
docker compose --profile hue up -d --build hue
```

After startup, the renderer is available at:

```text
http://localhost:8080/
```

Stop only the source Hue service:

```bash
docker compose --profile hue stop hue
```

## Java Application Architecture

### Technologies and Dependencies

- Java 21;
- Spring Boot 3.5.8;
- Spring Web and Jakarta Validation;
- Spring Security LDAP;
- Hadoop client 3.4.2;
- HBase client 2.6.2 for Hadoop 3;
- Hive JDBC standalone 4.0.1;
- Maven 3.9.11 in the build stage;
- Eclipse Temurin 21 JRE in the runtime stage.

The application is stateless: user sessions, Kerberos tickets, and query results are not stored in a local database.

### Request Flow and Security Model

1. The client sends LDAP credentials through HTTP Basic.
2. `LdapSecurityConfig` performs a bind using the FreeIPA DN template `uid={0},cn=users,cn=accounts`.
3. The health endpoint is available without login; all other endpoints require successful authentication.
4. `KerberosExecutor` obtains the login from `SecurityContextHolder`.
5. Configuration templates become the user principal and keytab path: `{user}@TEST.LOCAL` and `/run/keytabs/{user}.keytab`.
6. Hadoop `UserGroupInformation.loginUserFromKeytabAndReturnUGI` obtains Kerberos credentials.
7. Calls to HDFS, Kyuubi, HBase, or Ozone run in `UGI.doAs` on behalf of this user.

LDAP is responsible only for application authentication. Fine-grained role authorization in Spring is not implemented yet. The cluster protocols themselves are protected by Kerberos and receive the user's actual identity.

### Main Classes

| File | Purpose |
| --- | --- |
| `K8SparkUiApplication.java` | Spring Boot entry point. |
| `LdapSecurityConfig.java` | LDAP bind authentication, HTTP Basic, and health/API access rules. |
| `ClusterProperties.java` | Typed service addresses and principal/keytab templates. |
| `AppConfig.java` | Connects `ClusterProperties` to the Spring context. |
| `KerberosExecutor.java` | Selects the current LDAP user, logs in with the user's keytab, and calls `UGI.doAs`. |
| `KyuubiService.java` | JDBC connection, SQL execution, and conversion of `ResultSet` to JSON rows. |
| `HdfsService.java` | WebHDFS `LISTSTATUS` through `KerberosAuthenticator` and SPNEGO. |
| `HbaseService.java` | Native HBase connection and table listing. |
| `OzoneService.java` | Hadoop `FileSystem` for `ofs://` URIs and directory listing. |
| `ClusterController.java` | HTTP endpoints `/api/sql`, `/api/hdfs`, `/api/hbase/tables`, and `/api/ozone`. |

### HTTP API

| Method and path | Purpose | Body or parameter |
| --- | --- | --- |
| `GET /actuator/health` | Spring health | No authentication. |
| `POST /api/sql` | Kyuubi SQL | JSON `{"sql":"SELECT 1"}`. |
| `GET /api/hdfs` | WebHDFS `LISTSTATUS` | Query parameter `path`, default `/`. |
| `GET /api/hbase/tables` | HBase table listing | No parameters. |
| `GET /api/ozone` | OFS path listing | Query parameter `path`, default `/`. |

Default settings are in `src/main/resources/application.yml`. In the container, they are overridden by environment variables from `compose.yaml`.

### Building the Java Image

`docker/app/Dockerfile` uses a multi-stage build:

1. copies only `pom.xml` and downloads Maven dependencies in advance for an effective Docker cache;
2. copies `src` and builds an executable Spring Boot JAR;
3. copies only the JAR into a minimal Temurin 21 JRE runtime image.

The service publishes internal port `8080` as `localhost:8081` because host port `8080` is reserved for the optional source Hue.

## Detailed Test Environment Description

### Containers and Ports

| Compose service | Version or base image | Host ports | Role |
| --- | --- | --- | --- |
| `freeipa` | FreeIPA 4.13.1, Rocky Linux 9 | `88/tcp+udp`, `389`, `464/tcp+udp`, `636` | LDAP, Kerberos KDC, service principals, and keytabs. |
| `kyuubi` | `apache/kyuubi:1.10.1-spark` | `10009` | Kerberized HiveServer2/JDBC and Spark SQL `local[*]`. |
| `hdfs` | `apache/hadoop:3.4.2` | `9870` | NameNode, DataNode, and SPNEGO WebHDFS. |
| `hbase` | Apache HBase 2.6.2, Temurin 17 | `9090`, `16010` | Embedded ZooKeeper, HMaster, RegionServer, secure Thrift, and master UI. |
| `ozone` | `apache/ozone:2.0.0` | `9862`, `9874` | SCM, OM, DataNode, Object Store, and OFS. |
| `app` | Java 21 / Spring Boot | `8081` | k8spark-ui integration API. |
| `hue-reference` | `gethue/hue:latest` | `8082` | Official Docker Hub Hue for UI comparison. |
| `hue` | vendored Hue source renderer | `8080` | Optional source Hue, `hue` profile only. |

HDFS uses replication factor `1`. Ozone is also configured for single-node replication. HBase stores data in the local filesystem inside a named volume. This is a compact functional stand, not a production deployment.

### Startup Order and Health Checks

Compose dependency graph:

1. `freeipa` initializes the realm and runs `bootstrap-test-identities.service`;
2. after FreeIPA is healthy, `kyuubi`, `hdfs`, `hbase`, and `ozone` start in parallel;
3. each service waits for its keytabs and starts daemon processes;
4. `app` starts only after all five infrastructure services are healthy;
5. `hue-reference` is independent and starts by default;
6. `hue` starts only with the `hue` profile and depends on the entire Kerberos stand.

Health checks verify FreeIPA bootstrap, keytab availability, daemon processes, and service ports. Full read and write operations are performed by the separate `test-kerberos-services.sh`.

### Realm, Principals, and Keytabs

Test realm:

```text
TEST.LOCAL
```

Demo credentials for the new realm:

| Purpose | Identity | Password |
| --- | --- | --- |
| LDAP/Kerberos test user | `admin` / `admin@TEST.LOCAL` | `K8SparkAdmin2026Secure!` |
| FreeIPA Directory Manager | `cn=Directory Manager` | `DirectoryManager1` |

Both passwords are exposed in `compose.yaml` and the systemd bootstrap unit and are permitted only for the isolated local stand.

FreeIPA bootstrap creates:

| Principal | Keytab in `kerberos-shared` volume | Consumer |
| --- | --- | --- |
| `admin@TEST.LOCAL` | `admin.keytab` | Java API and manual client tests. |
| `kyuubi/kyuubi.test.local@TEST.LOCAL` | `kyuubi.keytab` | Kyuubi server. |
| `nn/hdfs.test.local@TEST.LOCAL` | `nn-hdfs.test.local.keytab` | HDFS NameNode. |
| `dn/hdfs.test.local@TEST.LOCAL` | `dn-hdfs.test.local.keytab` | HDFS DataNode. |
| `HTTP/hdfs.test.local@TEST.LOCAL` | `HTTP-hdfs.test.local.keytab` | WebHDFS SPNEGO. |
| `om/ozone.test.local@TEST.LOCAL` | `om-ozone.test.local.keytab` | Ozone Manager. |
| `scm/ozone.test.local@TEST.LOCAL` | `scm-ozone.test.local.keytab` | Storage Container Manager. |
| `dn/ozone.test.local@TEST.LOCAL` | `dn-ozone.test.local.keytab` | Ozone DataNode. |
| `HTTP/ozone.test.local@TEST.LOCAL` | `HTTP-ozone.test.local.keytab` | Ozone HTTP service identity. |
| `hbase/hbase.test.local@TEST.LOCAL` | `hbase-hbase.test.local.keytab` | HMaster, RegionServer, and Thrift. |
| `HTTP/hbase.test.local@TEST.LOCAL` | `HTTP-hbase.test.local.keytab` | HBase HTTP service identity. |

`bootstrap-test-identities` also creates the shared `/shared/krb5.conf`. Keytabs are not committed to Git: they are generated on first startup and stored in a Docker named volume. Infrastructure containers mount it as `/shared`, while the Java application mounts it read-only as `/run/keytabs`.

For compatibility between the Java 21 application client and Java 8 in the Kyuubi test image, service keytabs use AES-SHA1 enctypes. Production enctypes must be determined by your KDC policy and supported JDKs.

### Named volumes

| Volume | Contents |
| --- | --- |
| `freeipa-data` | FreeIPA realm, LDAP database, KDC, and PKI state. |
| `freeipa-run` | Writable `/run` for systemd and 389 Directory Server. |
| `freeipa-tmp` | Writable FreeIPA `/tmp` with the required filesystem semantics. |
| `kerberos-shared` | `krb5.conf`, user keytab, and service keytabs. |
| `hdfs-data` | NameNode metadata and DataNode blocks. |
| `hbase-data` | HBase and embedded ZooKeeper state. |
| `ozone-data` | SCM, OM, Ratis, and DataNode state. |

FreeIPA uses `cgroup: host` because systemd runs inside the container. `privileged` is intentionally not used.

### Configuration Directories

```text
src/main/java/com/k8spark/ui/     Java application code
src/main/resources/              Spring configuration and vendored UI resources
src/test/                        Spring tests
docker/app/                      Java multi-stage image
docker/freeipa/                  FreeIPA image and identity bootstrap
docker/kyuubi/                   Kerberos Kyuubi/Spark configuration
docker/hdfs/                     Kerberos HDFS 3.4.2 configuration
docker/hbase/                    Kerberos HBase 2.6.2 configuration
docker/ozone/                    Kerberos Ozone 2.0.0 configuration
docker/hue-reference/            Thin compatibility layer over Docker Hub Hue
docker/hue/                      Runtime config for source Hue
scripts/run-test-env.sh          Cross-platform environment launcher
scripts/test-kerberos-services.sh Functional Kerberos data-path tests
scripts/test-start-cycles.sh      Repeated restart and regression tests
compose.yaml                     Services, volumes, ports and dependencies
```

## macOS Intel and Apple Silicon Considerations

### Why `DOCKER_DEFAULT_PLATFORM` Must Not Be Set Globally

On M1/M2/M3/M4, setting `DOCKER_DEFAULT_PLATFORM=linux/amd64` makes Compose expect amd64 even from multi-arch images that the project builds natively. The result is an error such as:

```text
image ... was found but its platform (linux/arm64) does not match the specified platform (linux/amd64)
```

`run-test-env.sh` removes this variable for Compose, while `compose.yaml` sets `platform: linux/amd64` only for services that actually require it.

Check the global shell setting:

```bash
echo "${DOCKER_DEFAULT_PLATFORM-<not set>}"
```

### Docker Desktop Keychain `-67674`

If the Docker CLI cannot access the macOS credential helper, a public image pull may fail with a Keychain error. Project scripts use `docker/.docker-config` without a credential store only for public pulls/builds and do not change the user's Docker Desktop login.

Always prefer:

```bash
./scripts/run-test-env.sh -d
```

instead of manually running `docker compose build` if the `-67674` error appeared previously.

### Official Hue on Apple Silicon

Docker Hub Hue is distributed as an amd64 image. Under Rosetta, the regular `polars` wheel uses an unsupported CPU instruction and may terminate with `SIGILL`. `docker/hue-reference/Dockerfile` replaces only it with `polars-lts-cpu`; the Hue UI remains upstream.

## Known Limitations

- The stand is single-node and does not model a highly available production cluster.
- Logins, passwords, and generated keytabs are intended only for the local demo.
- The browser does not automatically obtain a Kerberos ticket from an LDAP password. The Java API uses a mounted user keytab.
- Production requires a secure credential broker or short-lived delegated credentials instead of long-lived user keytabs.
- The application does not yet provide role, row-level, or object-level authorization.
- `gethue/hue:latest` is intentionally used only as a moving reference; production builds must pin the digest.

## Verification After Configuration Changes

The minimum required set before handing off changes:

```bash
cd k8spark-ui
mvn test
./scripts/run-test-env.sh -d
./scripts/test-kerberos-services.sh
./scripts/test-start-cycles.sh 10
```

The change is considered safe for the test environment only after a real successful create/read/delete or put/get operation on every Kerberos data path, not merely after receiving a `healthy` status.
