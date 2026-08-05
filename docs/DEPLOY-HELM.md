# Production deployment of `kudos` with Helm

Deploy the **built** application outside test mode with the Helm chart in
`deploy/helm/kudos`. The test environment (FreeIPA, HDFS, Kyuubi, HBase, and
Ozone in Docker Compose) and image build are **not** part of the chart; see
[README.md](README.md) and [BUILD.md](BUILD.md).

- **Exposure:** only a `ClusterIP` `Service`; configure ingress or port
  forwarding outside the chart for external access.
- **TLS:** **HashiCorp Vault PKI** issues the certificate, the application
  authenticates to Vault through **AppRole**, and a **Vault Agent sidecar**
  delivers the PEM files to `/vault/secrets`. The Spring Boot SSL bundle in the
  `vault` profile uses `reload-on-update`, so certificate rotation does not
  restart the Pod.
- **Configuration and secrets:** the operator supplies external `ConfigMap`



```
```
                                       ├─► emptyDir /vault/secrets (tls.crt/tls.key/ca.crt)
sidecar: vault-agent (renew in place) ─┘
sidecar: vault-agent (renew in place) ─┘
```


The init container issues the certificate **before** the application starts;
the sidecar renews it before its lease expires. The agent configuration is

from values as environment variables.

## Requirements
- Опубликованный образ приложения (`docker/app/Dockerfile`, стадия `runtime` —
- A Kubernetes cluster and Helm 3+.
- A published application image built with the `runtime` stage of
  `dev/docker/app/Dockerfile`, accessible from the cluster; see
  [BUILD.md](BUILD.md).

- Network access from the Pod to WebHDFS, Kyuubi, HBase REST Gateway, Ozone OM,

Мирроринг того, что тестовый `docker/vault/bootstrap.sh` делает автоматически:

```bash
This mirrors what the test environment's `dev/docker/vault/bootstrap.sh` does
vault secrets enable pki
vault secrets tune -max-lease-ttl=87600h pki
vault write pki/root/generate/internal common_name="Example Root CA" ttl=87600h
vault write pki/roles/kudos \
  allowed_domains="svc.cluster.local,example.com" \
  allow_subdomains=true max_ttl=72h

vault write pki/roles/kudos \
vault auth enable approle
vault policy write kudos - <<'EOF'
path "pki/issue/kudos" { capabilities = ["create","update"] }
EOF
vault write auth/approle/role/kudos token_policies=kudos token_ttl=1h token_max_ttl=4h

path "pki/issue/kudos" { capabilities = ["create","update"] }
vault read  -field=role_id   auth/approle/role/kudos/role-id
vault write -f -field=secret_id auth/approle/role/kudos/secret-id
```

vault read  -field=role_id   auth/approle/role/kudos/role-id

```

The CN requested by the chart (`vault.commonName`) must be allowed by the PKI
role.

## External resources required by the release
|--------|--------------|------------|
The operator creates these resources in the target namespace. The table shows
the values names and defaults:

| Resource | Name (values) | Contents |
|----------|---------------|----------|

| ConfigMap | `externalConfig.krb5ConfigMap` = `kudos-krb5` | `krb5.conf` key |

```bash
kubectl create configmap kudos-config       --from-file=application.yml=./my-application.yml
kubectl create configmap kudos-krb5         --from-file=krb5.conf=/etc/krb5.conf
kubectl create configmap kudos-cluster-conf --from-file=core-site.xml --from-file=ozone-site.xml
kubectl create secret generic kudos-vault-approle \
  --from-file=role_id=./role_id --from-file=secret_id=./secret_id
```

kubectl create configmap kudos-cluster-conf --from-file=core-site.xml --from-file=ozone-site.xml

  --from-file=role_id=./role_id --from-file=secret_id=./secret_id
```

```yaml
spring:
  ldap:
    urls: ldap://ldap.example.com:389
    base: dc=example,dc=com
    username: cn=Directory Manager
```yaml
    user-dn-pattern: uid={0},cn=users,cn=accounts
kudos:
  api:
    base: dc=example,dc=com
    enabled: true
  cluster:
    webhdfs-url: http://namenode.example.com:9870
    kyuubi-url: "jdbc:hive2://kyuubi.example.com:10009/default;principal=kyuubi/kyuubi.example.com@EXAMPLE.COM"
    kyuubi-rest-url: http://kyuubi.example.com:10099
    hbase-rest-url: http://hbase.example.com:8080
    ozone-ofs-uri: ofs://omservice/
    ozone-conf-dir: /etc/ozone/conf
    spark-history-url: http://sparkhistory.example.com:18080
    kerberos-principal: "{user}@EXAMPLE.COM"
  features:
    editor: true
    files: true
    ozone: true
    hbase: true
    jobs: true
```

    files: true
    ozone: true
    hbase: true
    jobs: true
```

`kudos.api.enabled` defaults to `true`. For a UI-only deployment, set it to

`externalConfig.envFromSecret`, then perform a rollout or restart. The setting

```bash
helm install kudos deploy/helm/kudos \
  --namespace kudos --create-namespace \
  --set image.repository=registry.example.com/kudos \
  --set image.tag=0.1.0 \
  --set vault.address=https://vault.vault.svc:8200 \
  --set vault.commonName=kudos.kudos.svc.cluster.local
```

  --namespace kudos --create-namespace \

```bash
helm install kudos deploy/helm/kudos -n kudos --create-namespace -f my-values.yaml
```



```bash
kubectl -n kudos rollout status deploy/kudos
kubectl -n kudos port-forward svc/kudos 8443:8443
# https://localhost:8443/
```



kubectl -n kudos rollout status deploy/kudos
|------|--------------|------------|
# https://localhost:8443/
```

## Key `values.yaml` settings

| Key | Default | Purpose |
|-----|---------|---------|
| `image.repository` / `image.tag` | `kudos` / appVersion | application image |
| `service.type` / `service.port` | `ClusterIP` / `8443` | service; ClusterIP only |
| `springProfiles` | `vault` | active profile; TLS through Vault |
| `externalConfig.appConfigMap` | `kudos-config` | ConfigMap with `application.yml` |
| `externalConfig.krb5ConfigMap` | `kudos-krb5` | ConfigMap with `krb5.conf` |
| `externalConfig.clusterConfigMap` | `kudos-cluster-conf` | ConfigMap with `core-site.xml` and `ozone-site.xml` |
| `externalConfig.envFromSecret` | `""` | optional Secret with configuration environment variables |

| `vault.pkiPath` / `vault.pkiRole` | `pki` / `kudos` | PKI engine and issuing role |

| `vault.ttl` | `24h` | certificate TTL |

| `vault.caConfigMap` | `""` | optional ConfigMap with `ca.crt` trusted by Vault API |

```bash
scripts/hybrid-up.sh            # build → docker up → k8s prereqs → helm install → port-forward
# UI: https://localhost:8443/   (admin / KudosAdmin2026Secure!)
scripts/hybrid-down.sh          # снести (добавить --purge, чтобы удалить namespace и volume'ы)
```

You can exercise production mode locally: run the **cluster and Vault in Docker

(docker-desktop). The Pod reaches Docker services through their published ports;

match.

```bash
dev/scripts/hybrid-up.sh            # build → docker up → k8s prereqs → helm install → port-forward
# UI: https://localhost:8443/   (admin / KudosAdmin2026Secure!)
dev/scripts/hybrid-down.sh          # stop; add --purge to remove the namespace and volumes
kubectl -n kudos exec deploy/kudos -c app -- ls -l /vault/secrets
```

HTTPS, Editor (Kyuubi), Files (HDFS/WebHDFS), and Ozone**.

`vault.address`.
