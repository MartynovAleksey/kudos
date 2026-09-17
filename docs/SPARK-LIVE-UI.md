# Design: the live Spark UI hook

The Jobs screen used to be able to open only finished applications. The Spark History Server
learns about an application from its event log, and that log is complete only after the
application ends; while a query runs there is nothing to read from it. The stand made this
worse by setting `spark.ui.enabled=false` on the engines, so a running Kyuubi engine served no
UI at all — the one screen a user wants while a query is slow did not exist.

The hook closes that gap from the other side: the driver itself tells KUDOS where its own Spark
UI is listening, and KUDOS proxies that address under `/spark-ui/running/<appId>` for as long as
the driver answers. Finished applications are still read from the history server; only the
proxied upstream differs.

## Pipeline

```
spark driver (a Kyuubi engine)                    app
KudosUiPlugin
  registerMetrics  ──POST────▶  /engine-api/spark/running        RunningSparkApplications
  shutdown         ──DELETE──▶  /engine-api/spark/running/<id>     (in memory)

browser
  /spark-ui/running/<appId>/jobs/  ──▶  SparkUiProxyController  ──▶  http://<driver-host>:4040/jobs/
```

The plugin implements Spark's `SparkPlugin`/`DriverPlugin` API (stable since Spark 3.0) and runs
only on the driver: executors have no UI to report.

Registration happens in `registerMetrics`, not in `init`. That is the first callback Spark makes
with the application id assigned, and by then the UI is bound, so `SparkContext.uiWebUrl` reports
the port the driver actually got — 4040 is only a starting point, a second engine on the same
host lands on 4041.

Nothing in the plugin is allowed to fail an application. A missing setting, an unreachable KUDOS
or a rejected token is logged and forgotten, and the connect/read timeouts are 2 s/3 s because
both calls sit on the driver's startup and shutdown paths. The cost of a KUDOS that is down is a
job without a live UI, not a job that did not start.

The optional `spark.kudos.sessionId` ties the engine back to the KUDOS session that asked Kyuubi
to start it (`KyuubiService` appends it to the JDBC session configuration). Until an engine
registers, Jobs shows the session as a placeholder row; once the registration arrives carrying
that session id, the placeholder is dropped and the real application takes its place instead of
being listed twice.

## Building the jar

`spark-plugin/` is a standalone Maven project with its own `pom.xml` and no parent: the jar is
loaded by a Spark driver, not by the KUDOS application, and shares neither its Spring Boot
dependency management nor its release cycle. `spark-core` is a `provided` dependency and nothing
is bundled, so the jar adds no transitive dependency to an application that loads it.

```bash
mvn -f spark-plugin/pom.xml package
# -> spark-plugin/target/kudos-spark-plugin-0.1.0.jar
```

It is compiled against Spark 3.5 on Scala 2.12 with `maven.compiler.release=17`, matching the
engines in the stand.

The jar must be on the **driver's** classpath before the `SparkContext` starts, because
`spark.plugins` is instantiated during startup. `$SPARK_HOME/jars` on the machine that runs the
driver is the reliable place for that; `--jars` is not, since it ships jars to executors and
arrives too late for a driver plugin.

In the stand this is automatic:

* `dev/docker/app/Dockerfile.build` builds the plugin as a second Maven project in the same build
  stage as the application and exports `kudos-spark-plugin-0.1.0.jar` next to the fat jar.
* `dev/scripts/run-test-env.sh` copies it out of the build container into `dist/`, then into
  `dev/docker/kyuubi/kudos-spark-plugin.jar` — the Kyuubi image is built from `dev/docker`, so the
  jar has to be inside that context.
* `dev/docker/kyuubi/Dockerfile` moves it into the bundled Spark's `jars/`, where every engine
  the Kyuubi server forks picks it up.
* `dev/docker/kyuubi/start-kerberos-kyuubi` templates `spark.kudos.url` from the `KUDOS_URL`
  environment variable, defaulting to the Compose address. The two stands reach the application
  under different names — the `app.test.local` container under Compose, the `kudos` Service
  (`kudos.test.local`) in Kubernetes — so the Kubernetes chart sets
  `services.kyuubi.kudosUrl` (`deploy/helm/kudos-stand/values.yaml`) and the same image serves
  both.

## Attaching it to a Spark application

Any Spark 3.x application, with the jar in `$SPARK_HOME/jars` on the driver:

```properties
spark.plugins        com.kudos.spark.KudosUiPlugin
spark.ui.enabled     true
spark.kudos.url      https://kudos.example.org
spark.kudos.token    <the value of kudos.spark.registration-token>
# Optional, set by KUDOS itself for Kyuubi sessions:
# spark.kudos.sessionId  <KUDOS session id>
```

| Setting | Meaning |
| --- | --- |
| `spark.plugins` | Must contain `com.kudos.spark.KudosUiPlugin`. Spark takes a comma-separated list, so an existing value is extended, not replaced. |
| `spark.ui.enabled` | The plugin has nothing to report with the UI off; it logs that and skips registration. |
| `spark.kudos.url` | Base URL of the KUDOS application, without a path. |
| `spark.kudos.token` | The shared registration token. Blank or unset means no registration at all. |
| `spark.kudos.sessionId` | Optional. The KUDOS session whose placeholder row this engine replaces. |

The stand sets exactly these in `dev/docker/kyuubi/kyuubi-defaults.conf`, so every engine of
every user registers without the user configuring anything.

## The KUDOS side

`kudos.spark.*`, bound by `SparkRegistrationProperties`:

| Setting | Environment variable | Meaning |
| --- | --- | --- |
| `kudos.spark.registration-token` | `KUDOS_SPARK_REGISTRATION_TOKEN` | The shared secret the plugin presents as `Authorization: Bearer`. **Blank (the packaged default) turns the `/engine-api` transport off entirely.** |
| `kudos.spark.ui-hosts` | `KUDOS_SPARK_UI_HOSTS` | Hosts whose UI may be proxied: exact names, `*.suffix` patterns, or an IPv4 prefix such as `10.`. Empty (the default) accepts nothing. |
| `kudos.spark.running-ttl` | `KUDOS_SPARK_RUNNING_TTL` | How long a registration survives without being removed by the driver. Default `12h`. |

`/engine-api` gets its own security chain (`LdapSecurityConfig.engineApiSecurity`, order 0),
because an engine carries neither an LDAP identity nor a browser session: no session, no CSRF, no
login page to be redirected to, and `EngineTokenFilter` is the whole authorization. With a blank
token the path answers `404` rather than advertising itself with a `401` — a KUDOS that does not
expect engines looks like a KUDOS that has no such endpoint. A wrong token answers `401`; the
comparison is constant-time.

The UI address is supplied by the caller, so it is never taken on trust: `RunningSparkApplications`
rejects a registration whose host is outside `ui-hosts` with `403`, which is what keeps the proxy
from being turned into an arbitrary outbound request. An empty allowlist therefore accepts
nothing rather than everything. The stand uses `*.test.local,*.cluster.local,10.` — Compose
publishes the engines as `kyuubi.test.local`, while in Kubernetes the driver reports whatever its
pod address resolves to: the bare pod IP, or a cluster DNS name when a Service selects the pod.

Proxy access is the same owner check as the history UI (`SparkApplicationAccessService.canView`):
an administrator sees everything, a user only applications whose Spark user is their own login.
A registration for an application id that is not (or no longer) registered answers `404`, and the
history UI is where a finished application is read from.

An entry leaves the registry in one of three ways: the driver removes it on shutdown, the proxy
removes it after the driver stops answering (nobody else will, and a Jobs row that opens onto
nothing is worse than no row), or it ages out after `running-ttl` — the backstop for a driver
that was killed.

## Limitations

**Hybrid mode.** `dev/scripts/hybrid-up.sh` runs the application in Kubernetes while the cluster
stays in Docker Compose, and the app pod reaches the Docker services over host-published ports.
`compose.yaml` publishes Kyuubi's `10009` and `10099` but not the engines' UI ports, which are
assigned one per engine from 4040 upwards. So in hybrid mode a driver can register itself, but
the proxy cannot reach the address it registered unless those ports are published on the Docker
host as well. Plain Compose and the Kubernetes stand are unaffected: there the application and
the engines share a network.

**The proxy is GET-only.** `SparkUiProxyController` maps `GET /spark-ui/**` and nothing else, so
the live UI's POST actions — "kill job" and "kill stage" — do not work through KUDOS. The pages
render, the buttons do not. Killing a query is done from the Editor, through the Kyuubi session
that started it.

**TLS and the engines' truststore.** With the `vault` profile on, the application serves HTTPS
with a certificate issued by Vault's PKI, and `spark.kudos.url` becomes
`https://app.test.local:8443`. The plugin registers over a plain `HttpURLConnection`, which
validates against the JVM default truststore of the *driver*, so each engine needs the Vault PKI
CA imported into its truststore. Without it registration fails with a TLS handshake error, is
logged by the driver and otherwise ignored — the job runs, only its live UI is missing. This is
why the stand defaults to plain HTTP on port 8443.
