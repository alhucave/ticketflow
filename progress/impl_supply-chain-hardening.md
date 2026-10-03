# Informe: F-023 parte 2 (supply-chain-hardening)

Rama `feature/F-023b-supply-chain-hardening` (4 commits locales, sin push/PR). F-023 sigue `in_progress` (no marcado `done`).

## Archivos

Nuevos: `.github/workflows/security.yml`, `.github/dependabot.yml`, `.gitleaks.toml`, `.trivyignore`, `gradle.lockfile`, `docker/healthcheck/Healthcheck.java`, `docs/security.md`, `SECURITY.md`.
Modificados: `build.gradle.kts` (dependencyLocking + `jackson-bom` 3.1.7), `Dockerfile`, `docker-compose.yml`, `README.md`, `docs/verification.md`, `docs/architecture.md` (referencia de seccion), `progress/current.md`.
`ci.yml` y el job `verify` no se tocaron.

## Decisiones de diseno

- **Locking**: `dependencyLocking { lockAllConfigurations() }`; flujo `./gradlew dependencies --write-locks` documentado (README, docs/security.md, verification.md). Dockerfile copia `gradle.lockfile`.
- **Hallazgo real corregido**: Trivy marco 5 HIGH fixables en Jackson 3.1.5 (jackson-core/databind: CVE-2026-89407, -89425, -68497, -91776, -91777). Fix: `platform("tools.jackson:jackson-bom:3.1.7")` (Gradle elige la version mas alta); retirar cuando el BOM de Spring Boot lo incluya.
- **Trivy** via `aquasecurity/trivy-action` v0.36.0 (SHA `ed142fd0...`, tag anotado desreferenciado con `gh api`), Trivy v0.75.0 fijado. `--ignore-unfixed`, HIGH/CRITICAL, `exit-code 1`, `.trivyignore` (vacio, con formato id/motivo/fecha).
- **Secretos**: gitleaks v8.30.1 ejecutado con `docker run` de la imagen fijada por digest (no hay accion de terceros adicional), modos `git` (historial, `fetch-depth: 0`) y `dir`. Allowlist unica y estrecha en `.gitleaks.toml`: solo regla `generic-api-key`, solo `src/test/java/**/*.java` (34 hallazgos en historial / 17 en arbol, todos literales de test; ninguna otra regla excluida). Compose/.env.example/docs no disparan nada.
- **Hallazgo de robustez (importante)**: con un propietario distinto (runner de CI) git rechaza el repo por `safe.directory` y gitleaks termina con "0 commits scanned / no leaks found" y exit 0 (falso negativo, reproducido localmente con `--user`). Mitigacion en el workflow: `GIT_CONFIG_*` para `safe.directory` + el paso falla si ve `0 commits scanned` o `ERR`. Probado: con la guarda, sin ella y con el fix.
- **Imagen**: `docker build` sin push + Trivy image. Sin SARIF (sin `security-events`); permisos `contents: read`.
- **Dependabot**: gradle, github-actions, docker; semanal, agrupado, limite de PR abiertas 3/3/2. Imagenes de compose (terceros, dev) y Trivy/gitleaks del workflow se actualizan a mano (documentado).
- **Dockerfile**: runtime `gcr.io/distroless/java25-debian13:nonroot` fijado por digest (sha256:ca60da13...), build `eclipse-temurin:25-jdk` por digest (sha256:8c0a84ea...), ambos verificados con `docker pull`/`inspect`. Sin shell ni curl (`docker exec id` -> "executable file not found"). Flags: `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:-UsePerfData --enable-native-access=ALL-UNNAMED` (esta ultima elimina un WARNING de Netty). HEALTHCHECK con una clase Java minima compilada en la etapa de build (sin paquetes extra; JVM de 16 MB). Imagen 120 MB (antes 166 MB comprimidos/603 MB en disco).
- **Compose**: `127.0.0.1:` en los tres puertos; `read_only`, `tmpfs`, `cap_drop: [ALL]`, `no-new-privileges`, `mem_limit`/`cpus`/`pids_limit`, `restart: unless-stopped` en los tres servicios. Hallazgos al endurecer: DynamoDB Local necesita `/tmp:exec` (carga una libreria nativa SQLite; el `tmpfs` es `noexec` por defecto: sin ello 500 en todo acceso aunque el healthcheck "pase"); LocalStack necesita tmpfs en `/tmp`, `/var/lib/localstack` y `/root/.cache`, y sigue corriendo como root (sin capacidades). Healthcheck de compose para app hereda el de la imagen.

## Escaneos locales (Trivy 0.75.0 / gitleaks 8.30.1 en Docker via Colima)

| Escaneo | Antes | Despues |
|---------|-------|---------|
| Trivy fs (lockfile) HIGH/CRITICAL fixable | 5 HIGH (Jackson 3.1.5) | 0 |
| Trivy image (`ticketflow:local`) fixable | - | 0 (debian 13.7: 0, app.jar: 0) |
| Trivy image sin `--ignore-unfixed` | - | 8 HIGH sin parche: libexpat1 (CVE-2026-66046, -76956, -76957, -93990), libuuid1 (CVE-2026-76642, -78408, -78409, -78410); documentados como aceptados, no necesitan entrada en `.trivyignore` |
| gitleaks historial (92 commits) con config | 34 sin config | 0 |
| gitleaks arbol con config | 17 sin config | 0 |
| Prueba negativa | clave AWS plantada y borrada en historial de prueba -> detectada (1) | |
| actionlint 1.7.12 (`rhysd/actionlint`) | - | sin errores (security.yml, ci.yml, release.yml) |

## Pruebas por ejecucion (docker-compose real, hardened)

`docker-compose up --build -d`: 3 servicios `healthy`, `/actuator/health` -> `{"status":"UP"}`. Flujo completo con curl: crear evento (capacidad 20) -> `POST /orders` con `Idempotency-Key: hardening-flow-key-0001` qty 3 -> `202 RESERVED` -> sondeo -> `SOLD`; disponibilidad `{"available":17,"sold":3,...}`; replay misma clave -> `202`; clave corta -> `400`.

`docker inspect` (app / dynamodb / localstack):
```
app        user="nonroot"        readonly=true capdrop=[ALL] secopt=[no-new-privileges:true] mem=536870912 nanocpus=1000000000 pids=200  restart=unless-stopped ports={"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"8080"}]}
dynamodb   user="dynamodblocal"  readonly=true capdrop=[ALL] secopt=[no-new-privileges:true] mem=805306368 nanocpus=1000000000 pids=256 restart=unless-stopped ports=127.0.0.1:8000
localstack user=""(root)         readonly=true capdrop=[ALL] secopt=[no-new-privileges:true] mem=1610612736 nanocpus=2000000000 pids=1024 restart=unless-stopped ports=127.0.0.1:4566
```
`docker port`: `8080/tcp -> 127.0.0.1:8080`, `8000/tcp -> 127.0.0.1:8000`, `4566/tcp -> 127.0.0.1:4566`. `lsof -nP -iTCP -sTCP:LISTEN`: solo `127.0.0.1:{4566,8000,8080}` (proceso ssh de Colima), ninguna escucha en `*`. PID 1 de la app corre como uid 65532. `docker stats`: app 175 MiB / 512 MiB. Log de la app: sin ERROR/WARN/excepciones (el WARNING de acceso nativo de Netty se elimino con `--enable-native-access`). `docker-compose down -v` ejecutado al final.

## `./init.sh`

- `./init.sh` (tras locking y Jackson): `BUILD SUCCESSFUL`, `==> init.sh OK`.
- `INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST de Colima + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE): `BUILD SUCCESSFUL in 3m 8s`, `==> init.sh OK`.

## Solo lo valida el primer CI real

- Ejecucion de `security.yml` en runners de GitHub (descarga de la accion por SHA, cache y DB de Trivy desde el runner, `docker build` en el runner, ownership real del checkout con la guarda de gitleaks, pull de `ghcr.io/gitleaks/gitleaks` desde ghcr).
- Que `ci.yml`/`verify` siga verde con el lockfile (se probo `./init.sh` local con y sin integracion, no en GitHub) y que la cache de `setup-java` (gradle) acepte el lockfile.
- Dependabot: se activa solo al estar `dependabot.yml` en la rama por defecto (no hay validador local; el YAML parsea). La etiqueta `dependencies` la crea Dependabot si falta.
- El cron semanal solo corre en la rama por defecto. Los hallazgos de la ejecucion semanal pueden diferir (DB de Trivy cambia a diario).
- Arquitectura: la imagen se construyo/probo en arm64 (Colima); CI construye amd64 (los digests fijados son indices multi-arch).

## Notas para el reviewer

- `docs/security.md` verificado contra el codigo: SHA-256 en `OrderId`, `MessageDigest.isEqual` en `AdminKeyGuard`, regex del correlation id, actor `complimentary-issuance`, redrive/DLQ en `init-queues.sh`.
- Imagenes de terceros de compose (DynamoDB Local, LocalStack) no se escanean en CI (documentado como limitacion).
