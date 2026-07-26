# Building the `kudos` image

Сборка приложения выполняется **в Docker** через multi-stage `docker/app/Dockerfile`.
Тестовый стенд (compose) и прод-запуск (Helm) — отдельные части, см.
[README.md](README.md) и [docs/DEPLOY-HELM.md](DEPLOY-HELM.md).

## Build outputs

`docker/app/Dockerfile` — multi-stage:

1. **build** — копирует `pom.xml`, скачивает зависимости (кэш слоёв Docker),
   затем копирует `src` и собирает исполняемый Spring Boot JAR (`mvn package`).
2. **runtime** (стадия по умолчанию) — переносит только JAR в минимальный
   Temurin 21 JRE. Это и есть образ приложения.

Стадия по умолчанию — `runtime`, поэтому обычная сборка даёт готовый к запуску
образ:

```bash
DOCKER_BUILDKIT=1 docker build -f docker/app/Dockerfile -t kudos:0.1.0 .
```

Тот же образ собирает и тестовый compose (`docker compose build app`).

> baked into the image: Vault PKI issues it and a Vault Agent sidecar delivers
> it to `/vault/secrets` for the `vault` profile. See
> [DEPLOY-HELM.md](DEPLOY-HELM.md).

## Local build without Docker (optional)

JDK 21 and Maven 3.9+ are required:

```bash
mvn -DskipTests package      # target/kudos-0.1.0.jar
mvn test                     # unit + Spring context tests
```

The local run uses ordinary HTTP because the `vault` profile is not enabled;
no certificate is required.

## Optional security-scanning stages

В `docker/app/Dockerfile` есть дополнительные стадии, которые **не** собираются
при обычной сборке (не входят в граф стадии `runtime`). Каждая сканирует
built fat JAR and exports a Markdown table through `--output`. BuildKit is

Trivy → `./trivy.md`:

```bash
DOCKER_BUILDKIT=1 docker build -f docker/app/Dockerfile \
  --target trivy-report \
  --output type=local,dest=. .
```


OWASP Dependency-Check produces `./owasp.md`; pass the NVD key as a file-based

```bash
DOCKER_BUILDKIT=1 docker build -f docker/app/Dockerfile \
  --target owasp-report \
  --secret id=owasp_key,src=$HOME/OWASP-API.key \
  --output type=local,dest=. .
```


`mvn -Powasp verify`) — см. `pom.xml` и `scripts/scan-*.sh`. Разбор применимости
verify`, `mvn -Powasp verify`); see `pom.xml` and `dev/scripts/scan-*.sh`.
`trivy_analyzed.md` and `owasp_analyzed.md` assess whether reported CVEs apply

security report.

## Publishing the image


```bash
docker tag  kudos:0.1.0 registry.example.com/kudos:0.1.0
docker push registry.example.com/kudos:0.1.0
```
