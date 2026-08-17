# Building the `kudos` image

The application build uses **two Dockerfiles**: the build image compiles and
exports artifacts, while the runtime image packages them into a minimal image.
The test environment (Compose) and production deployment (Helm) are separate;
[docs/DEPLOY-HELM.md](DEPLOY-HELM.md).

## Build outputs

**1. `dev/docker/app/Dockerfile.build`** compiles the Spring Boot JAR and the
health-check class on a Maven/JDK image and **exports them to the host** in

```bash
DOCKER_BUILDKIT=1 docker build -f dev/docker/app/Dockerfile.build \
  --output type=local,dest=dist .
# -> dist/kudos-0.1.0.jar, dist/HealthCheck.class
```


**2. `dev/docker/app/Dockerfile.runtime`** takes the prepared artifacts from
`./dist` and places them in a **distroless** image
(`gcr.io/distroless/java21-debian12:nonroot`). The image contains **neither

```bash
docker build -f dev/docker/app/Dockerfile.runtime -t kudos:0.1.0 .
```

```

> Distroless is a minimal JVM runtime without a shell or package manager,
> minimizing CVEs. A true `FROM scratch` image is not suitable for a JVM
> application because it contains no JVM, libc, or linker to run `java -jar`.

> JRE and a precompiled `HealthCheck.class`; Kubernetes uses an `httpGet` probe.


> baked into the image: Vault PKI issues it and a Vault Agent sidecar delivers
> it to `/vault/secrets` for the `vault` profile. See
> [DEPLOY-HELM.md](DEPLOY-HELM.md).

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

`dev/docker/app/Dockerfile.build` provides additional stages that are **not**
built during a normal artifact build (`--target artifacts`). Each scans the
built fat JAR and exports a Markdown table through `--output`. BuildKit is

Trivy → `./trivy.md`:

```bash
DOCKER_BUILDKIT=1 docker build -f dev/docker/app/Dockerfile.build \
  --target trivy-report \
  --output type=local,dest=. .
```


OWASP Dependency-Check produces `./owasp.md`; pass the NVD key as a file-based

```bash
DOCKER_BUILDKIT=1 docker build -f dev/docker/app/Dockerfile.build \
  --target owasp-report \
  --secret id=owasp_key,src=$HOME/OWASP-API.key \
  --output type=local,dest=. .
```


The same scanners are available as optional Maven profiles (`mvn -Ptrivy
verify`, `mvn -Powasp verify`); see `pom.xml` and `dev/scripts/scan-*.sh`.
`trivy_analyzed.md` and `owasp_analyzed.md` assess whether reported CVEs apply

security report.

## Publishing the image


```bash
docker tag  kudos:0.1.0 registry.example.com/kudos:0.1.0
docker push registry.example.com/kudos:0.1.0
```
