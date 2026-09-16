# Running the test stand in Kubernetes

**This is the primary test stand.** The data services and engines run in Kubernetes;
FreeIPA and Vault keep running in Docker Compose, because FreeIPA needs systemd with
a host cgroup and Vault is bootstrapped against it. Compose remains supported as a
second way to run everything (`dev/scripts/run-test-env.sh`), and `compose.yaml`
remains the source of truth for what the stand contains — but the stand that runs,
and the one changes are verified against, is the one in the cluster.

```bash
docker compose up -d freeipa vault vault-agent   # the two that stay behind
dev/scripts/k8s-up.sh                            # everything else, in the cluster
dev/scripts/k8s-redeploy.sh app                  # apply a change to the running stand
dev/scripts/k8s-down.sh                          # remove it again
```

The UI answers at **http://localhost:30443/** once the stand is up: `k8s-up.sh`
installs the application with `service.type=NodePort` and a pinned
`service.nodePort`, so the address is the same after every restart and no
`kubectl port-forward` has to be held open. The production chart keeps its
ClusterIP default; only the stand pins the port.

The chart lives in `deploy/helm/kudos-stand`; the application reuses
`deploy/helm/kudos` unchanged, overriding only its service exposure.

## How the three load-bearing pieces are replaced

**Names.** Every service is addressed as `<name>.test.local`, because that name is
in its Kerberos principal and in its own configuration files. `k8s-up.sh` installs
a `test.local` zone into CoreDNS: exact-name blocks for FreeIPA and Vault, pointing
at the Docker host, and a rewrite for everything else into the stand's namespace.
Zone specificity does the selecting — plugin order inside one CoreDNS block is
fixed by the build and cannot be used for this. The rewrite carries `answer auto`
so replies keep the queried name; without it a Kerberos client canonicalises the
name and asks for a principal that was never issued.

**Shared state.** Compose shares one named volume at `/shared`: FreeIPA drops
keytabs in it and Ozone publishes the S3 secret it mints at startup. Here that is
one read-write-many claim mounted at the same path, seeded from a Secret by an init
container. The copy must dereference symlinks (a Secret mounts as links into
`..data`) and set mode 0644, because a Secret arrives 0400 root and Trino runs as
uid 1000.

**Ordering.** `depends_on: service_healthy` becomes an init container that waits on
the predecessor's port, and each compose healthcheck becomes a readiness probe with
the same condition. Probes dial the pod's own address from the downward API: not
every daemon binds loopback, and a probe that dials its own Service can never pass,
because a Service has no endpoints until the pod is ready.

## What each service needed

| Service | Concession |
| --- | --- |
| HDFS | `hostNetwork` (its secure DataNode uses privileged ports) with `ClusterFirstWithHostNet`, a `hostAliases` entry pointing its own name at the node, and `*-bind-host` set to 0.0.0.0 |
| HBase | `hbase.zookeeper.quorum` set to the pod's short name: HBase compares names, and a pod hostname cannot contain dots |
| StarRocks | privileged, as in compose; no data volume, because the image keeps its entrypoint under `/data` |
| Flink | `args`, not `command` — a Kubernetes `command` replaces the entrypoint rather than the CMD |
| Trino | keystore built from the certificate Vault issued, not a self-signed one |
| all | `strategy: Recreate`; one instance, one claim, and a rolling update deadlocks |

Kerberos in the cluster prefers TCP (`udp_preference_limit = 1` in the cluster's copy
of `krb5.conf`): UDP to the KDC across the Docker Desktop gateway hangs instead of
falling back. `_HOST` is substituted with the concrete name when configuration is
copied out of `dev/docker`, because a pod's canonical hostname is always a
`cluster.local` name and no DNS arrangement changes that.

## Applying a change

`dev/scripts/k8s-redeploy.sh [app|kyuubi|hdfs|ozone|hbase|gravitino|trino|spark-history|log-collector]`
rebuilds those images, retags them for the stand, refreshes the `kudos-config`
ConfigMap from `config/application.yml` and restarts just those workloads. It takes
a minute or two; a full `k8s-up.sh` takes twenty. `app` is the default.

A change under `deploy/helm/**` is not an image change — apply it with
`helm upgrade` (`--reuse-values` plus the settings that changed) or a full
`k8s-up.sh`.

## Verifying

```bash
kubectl -n kudos-stand get pods
APP_BASE=http://localhost:30443 dev/scripts/test-sql-engines.sh
```

## Known gap

`dev/scripts/test-kerberos-services.sh` reaches its services with `docker exec` on
compose container ids, so it cannot run against the cluster without an abstraction
over "execute in service X". Kerberos itself is exercised by the SQL suite above,
which authenticates every engine with a keytab.
