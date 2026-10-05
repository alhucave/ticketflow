# Informe de implementación: F-032 first-release-ghcr

Rama `feature/F-032-first-release-ghcr`. Estado en `feature_list.json`: `in_progress` (no se marcó `done`). **No se creó ningún tag, release ni push; no hubo `docker login` ni pull desde ghcr.io.** El primer publish lo hace la sesión principal tras el merge (procedimiento y lista de comprobación al final).

## Archivos

Creados:
- `scripts/require-commit-on-main.sh`: gate 1, el commit etiquetado es `main` o ancestro (API compare).
- `scripts/require-green-verify.sh`: gate 2, check run `verify` de GitHub Actions completado con éxito (espera si corre).
- `scripts/smoke-image.sh`: prueba de humo de una imagen sin infraestructura (se usa igual en local y en el workflow).
- `scripts/test-release-scripts.sh`: prueba offline de los dos gates con un `gh` simulado (13 casos); la ejecuta `./init.sh`.

Modificados:
- `.github/workflows/release.yml`: reescrito (gate, publish, smoke; permisos por job; tags; setup-buildx; build-arg).
- `Dockerfile`: `ARG APP_VERSION` (defecto `0.0.1-SNAPSHOT`) -> `-PappVersion`.
- `build.gradle.kts`: `version = providers.gradleProperty("appVersion").getOrElse("0.0.1-SNAPSHOT")`.
- `docker-compose.yml`: `image: ${TICKETFLOW_IMAGE:-ticketflow:local}` (para arrancar la imagen publicada con `--no-build`).
- `init.sh`: ejecuta `scripts/test-release-scripts.sh` (tras `--check-runners`, antes de Gradle).
- `docs/decisions.md`: nueva `DP-040` (Extra propio) + fila de índice; `DP-029` actualizada (ya no dice «nunca se ha ejecutado»; enlaza DP-040).
- `README.md` (tabla CI/CD, «Usar la imagen publicada», «Publicar un release», imagen suelta), `docs/verification.md` (sección de verificación del release).
- `progress/current.md`, este informe. `feature_list.json` solo tenía ya la entrada F-032 (sin tocar).

## Ítem 1: revisión de `release.yml` contra la realidad

**Mapeo tag -> tags de imagen.** No se adivinó: se descargó `dist/index.cjs` de `docker/metadata-action` en el SHA fijado (`dc802804...`, v6.2.0) y se ejecutó en un contenedor `node:24-slim` con un evento `push` de tag y un servidor local que devuelve los datos del repo (sin token). Resultado (columna «antes» = `release.yml` original con `type=raw,value=latest`; «ahora» = sin esa línea):

| tag git | antes | ahora |
|---|---|---|
| v0.1.0 | `0.1.0 0.1 latest latest` (**`latest` duplicado**) | `0.1.0 0.1 latest` |
| v0.1.1 | n/a | `0.1.1 0.1 latest` |
| v1.2.3 | n/a | `1.2.3 1.2 latest` |
| v0.2.0-rc.1 | `0.2.0-rc.1 latest` (**un prelanzamiento sería `latest`**) | `0.2.0-rc.1` |
| nightly | n/a | sin tags, advertencia (el patrón de disparo ya lo impide) |

Para major 0 no se genera `{{major}}` (no está en la config, y `{{major}}.{{minor}}` da `0.1`). `latest` sale del flavor por defecto `latest=auto` (solo semver no prerelease), por eso se quitó `type=raw,value=latest`. Etiquetas OCI generadas (incluida `org.opencontainers.image.source=https://github.com/alhucave/ticketflow`, `revision`, `version=0.1.0`) y comprobadas en una imagen construida con esas etiquetas (`docker image inspect`).

**Disparo.** `tags: ["v*"]` también aceptaba `vfoo` (sin tags -> build con lista vacía). Ahora `v[0-9]+.[0-9]+.[0-9]+` y `v[0-9]+.[0-9]+.[0-9]+-*`.

**Permisos.** Nivel de workflow `permissions: {}`; `gate`: `contents: read` + `checks: read`; `publish`: `contents: read` + `packages: write` (único con escritura); `smoke`: `contents: read` + `packages: read`. `concurrency` por ref sin cancelar. actionlint 1.7.12 limpio.

**Push con `GITHUB_TOKEN` a ghcr.io desde repo privado.** Soportado por GitHub cuando el job tiene `packages: write` y el paquete aún no existe (este es el caso: no hay paquete); el enlace con el repositorio sale de la etiqueta `source`. No se puede probar sin publicar: lo prueba el run real (ver lista).

**Build del Dockerfile como lo ejecuta la acción.** Con el plugin buildx 0.37.2 y un builder `docker-container`: `docker buildx build --build-arg APP_VERSION=0.1.0 --label ...(las de la acción) --attest type=provenance,mode=min,inline-only=true -o type=oci` (lo que `build-push-action` pasa en repos privados, leído en su `src/context.ts` del SHA fijado) produce un único manifiesto sin manifiestos extra de atestación; `--load` y `scripts/smoke-image.sh` sobre esa imagen: verde. Se añadió `docker/setup-buildx-action` porque la atestación por defecto no la soporta el driver `docker`.

**SHAs de acciones** (todas verificadas con `gh api repos/<o>/<r>/commits/<tag>`): `actions/checkout` v7.0.1 = `3d3c42e5...`; `docker/login-action` v4.6.0 = `dbcb8138...`; `docker/metadata-action` v6.2.0 = `dc802804...`; `docker/build-push-action` v7.4.0 = `c3c9e263...`; nuevo `docker/setup-buildx-action` v4.4.1 = `f87e5991a6d7451dcb8d9637bfbc97413f497069`. Las cuatro existentes coinciden con su tag y son la última release (`releases/latest`). No se usó `setup-qemu-action` (ver ítem 5).

## Ítem 2: gate

`gate` es el primer job y `publish` tiene `needs: gate`. Pasos: checkout, `scripts/require-commit-on-main.sh` y `scripts/require-green-verify.sh`, con `GH_TOKEN: ${{ github.token }}` a nivel de job. `require-green-verify.sh`: `gh api --paginate repos/R/commits/SHA/check-runs?check_name=verify&filter=all&per_page=100`, filtra `name == "verify"` y `app.slug == "github-actions"`; cualquier `completed success` basta (re-ejecuciones); si hay `queued/in_progress` espera hasta `GATE_WAIT_SECONDS` (1200) sondeando cada 30 s (el caso normal: tag justo después del merge, mientras corre el CI push de `main`); ninguna ejecución, fallo o error de API = falla con `::error::` claro. Además `require-commit-on-main.sh` exige que el commit esté en `main` (compare `identical|behind`): un tag sobre una rama sin fusionar no publica, aunque su CI esté verde.

Evidencia real (API de GitHub, sin publicar):
- Commit verde de main `8434d858...`: `OK: ... has a successful 'verify' check run (1 run(s) found)`, rc 0; `on main (compare status: identical)`.
- Commit inicial (sin CI): `Refusing to publish: ... no 'verify' check run exists for this commit ...`, rc 1.
- `c526b7e4...` (run fallido 37128517785): `found: completed failure;`, rc 1.
- SHA inexistente / repo inexistente: `Could not read the check runs ...` (HTTP 422/404), rc 1 (falla cerrado).
- Punta de la rama `feature/F-034-fix-startup-race` (squash-merged): `not on main (compare status: diverged)`, rc 1.
- Sondeo (con `gh` simulado): `in_progress` x2 -> `failure + success` -> OK (cualquier éxito); atascado `in_progress` con presupuesto de 3 s -> falla con mensaje.
- `scripts/test-release-scripts.sh`: 13 casos OK; control negativo: cambié `grep -qx 'completed success'` por `grep -q 'completed'` y el caso `[failed]` falló (`wanted fail, exit code 0`); restaurado.

## Ítem 3: versión

`build.gradle.kts` leía `version = "0.0.1-SNAPSHOT"` fijo, que pisa cualquier `-Pversion`; por eso la propiedad se llama `appVersion`. `Dockerfile`: `ARG APP_VERSION=0.0.1-SNAPSHOT` global + `ARG APP_VERSION` en la etapa; solo la capa `bootJar` depende de él (la caché de dependencias se conserva). `release.yml` pasa `APP_VERSION=${{ steps.meta.outputs.version }}`.

Pruebas: `docker build --build-arg APP_VERSION=0.1.0` y `docker-compose up --build` (defecto):
- Contenedor con build-arg: `"service":{"name":"ticketflow","version":"0.1.0"` y `Starting TicketflowApplication v0.1.0` en los logs.
- `docker-compose up --build -d --wait` por defecto: `"version":"0.0.1-SNAPSHOT"`; compra hasta `SOLD` con `./requests/demo.sh`; `docker-compose down -v` limpio.

## Ítem 4: smoke

Job `smoke` (`needs: publish`, `packages: read`, `contents: read` por el checkout del script): `docker/login-action` con `GITHUB_TOKEN`, comprueba que `digest` y `version` no estén vacíos y ejecuta `scripts/smoke-image.sh "$IMAGE_NAME@$IMAGE_DIGEST" "$IMAGE_VERSION" 90`. El script: `docker run -d` sin infraestructura con las restricciones de compose (`--read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges --memory 512m`), puerto 8081 publicado en loopback con puerto aleatorio, sondeo de `/actuator/health/liveness` hasta 200 con plazo, comprueba que el contenedor siga vivo, comprueba `service.version` en los logs, imprime estado y logs si falla, y `trap ... EXIT` siempre borra el contenedor. La readiness (503 sin infraestructura) solo se informa.

Probado en local con la imagen construida (sin ghcr): verde en 4 s (`OK: liveness 200 after 4s`, `readiness answers 503`, `OK: logs report service.version=0.1.0`), también sobre la imagen `amd64` emulada (33 s). Controles negativos: versión esperada `9.9.9` -> `SMOKE FAILED: expected service.version 9.9.9 ... found '0.1.0'` con logs; plazo de 1 s -> `liveness did not answer 200 within 1s`; imagen `busybox` que sale -> `the container did not start`; en todos `docker ps -a --filter name=ticketflow-smoke` queda vacío. Hallazgo: sin credenciales AWS el consumidor reintenta con backoff y la app sigue arriba (como dice DP-037); liveness UP.

## Ítem 5: plataformas (rechazada multi-arquitectura; imagen solo `linux/amd64`)

Evidencia (Colima aarch64, sin buildx instalado; usé el binario buildx v0.37.2 de la release de GitHub en un directorio `DOCKER_CONFIG` temporal y un builder `docker-container`):
- Bases fijadas por digest: `eclipse-temurin:25-jdk@sha256:8c0a84ea...` tiene `linux/amd64` y `linux/arm64/v8`; `gcr.io/distroless/java25-debian13:nonroot@sha256:ca60da13...` tiene `amd64`, `arm64/v8`, `s390x`, `ppc64le`, `riscv64` (`docker buildx imagetools inspect`).
- Variante con `FROM --platform=$BUILDPLATFORM` (copia en el scratchpad): `docker buildx build --platform linux/amd64,linux/arm64 -o type=oci` terminó en 1 min 34 s y el índice resultante tiene `amd64` y `arm64` (37 capas cada una), sin emulación.
- **Pero** esa variante falla con el constructor clásico: `docker build` y `docker-compose build app` en esta máquina (sin buildx) dan `failed to parse platform : "" is an invalid OS component of "": ...` (compose lo avisa: «buildx Docker CLI plugin not found: falling back to the classic builder»). Rompería el inicio rápido del README en esa instalación. Con el Dockerfile sin cambios, build nativo 1 min 37 s.
- La alternativa que no toca el Dockerfile (QEMU): compilar Gradle bajo emulación. Medido (amd64 sobre host arm64): 4 min 55 s frente a 1 min 37 s nativo, y JDK bajo QEMU es frágil; descartada.
- Decisión: solo `amd64` (el runner `ubuntu-24.04`, donde corre `smoke`). Camino para activar arm64 documentado en DP-040 (`FROM --platform=$BUILDPLATFORM` + `platforms:` + exigir buildx en local). `security.yml` (`docker build --tag ticketflow:scan .`, BuildKit en el runner) y `docker-compose` no cambian: ambos construyen el mismo Dockerfile (comprobado: compose `up --build --wait` OK, buildx OK).

## Ítem 6: documentación y procedimiento

README: «Usar la imagen publicada» (login con `gh auth refresh -s read:packages` + `docker login ghcr.io`, o PAT classic con `read:packages`; `TICKETFLOW_IMAGE=ghcr.io/alhucave/ticketflow:0.1.0 docker-compose pull app && docker-compose up -d --wait --no-build`; visibilidad: privado mientras el repo lo sea, cambiarlo es decisión del dueño) y «Publicar un release» (comandos exactos, lista de comprobación, deshacer un tag malo y qué queda en el registro). `TICKETFLOW_IMAGE` probado en local con la imagen construida: `docker-compose up -d --wait --no-build` -> app `healthy`, `service.version` 0.1.0, compra hasta `SOLD`. DP-040 registrada (Extra propio, F-032) y enlazada ya en `feature_list.json`; DP-029 actualizada; `./init.sh --check-registry` OK (40 decisiones).

## Ítem 7: validación (sin publicar)

- actionlint 1.7.12 (Docker) sobre los tres workflows: sin hallazgos, rc 0 (la primera pasada marcó SC2016 en el paso de resumen; corregido). shellcheck (`koalaman/shellcheck:stable`) sobre `scripts/*.sh` e `init.sh`: sin hallazgos.
- `./init.sh --check-runners`: `OK: 7 runs-on entries, none uses a floating label` (runners siguen en `ubuntu-24.04`).
- `./init.sh` completo: verde, `BUILD SUCCESSFUL in 1m 29s`, `==> init.sh OK`.
- `INCLUDE_INTEGRATION=true ./init.sh` (se tocó `build.gradle.kts`): verde, `BUILD SUCCESSFUL in 5m 27s`.
- Todo lo demás (gate, smoke, build-arg, compose) detallado en los ítems 1 a 6.

## Lo que SOLO prueba el run real del tag

1. Que `GITHUB_TOKEN` (con `packages: write`) puede crear el paquete `ghcr.io/alhucave/ticketflow` desde un repo privado y que queda enlazado al repo (etiqueta `source`).
2. El `digest` que devuelve `build-push-action` (aparece en el resumen del job `publish`) y la línea de log del push del manifiesto.
3. Que las tags `0.1.0`, `0.1` y `latest` existen (la lista local la produjo la acción en este entorno, no el registro).
4. El job `smoke` en el runner: login con `GITHUB_TOKEN` (`packages: read`) y pull por digest de un paquete privado, y el arranque en amd64 nativo.
5. Que `setup-buildx-action` + `build-push-action` en el runner aceptan la atestación `mode=min,inline-only` (localmente probada con buildx 0.37.2/buildkit 0.33).
6. Que la API de checks devuelve `verify` en el momento del tag (probada contra commits reales, pero no en el contexto de un job con `checks: read`).

## Procedimiento post-merge (sesión principal)

1. Merge del PR a `main`; esperar el CI push de `main` (`gh run list --workflow ci.yml --branch main --limit 1` en `completed success`; si aún corre, el gate espera hasta 20 min).
2. `git checkout main && git pull --ff-only && git tag -a v0.1.0 -m "ticketflow 0.1.0" && git push origin v0.1.0`.
3. `gh run watch <id del run Release> --exit-status` (o `gh run list --workflow release.yml`): `gate` -> `publish` -> `smoke` verdes.
4. Comprobar: resumen de `publish` (digest y tags `0.1.0`, `0.1`, `latest`); `gh auth refresh -s read:packages` (lo hace el usuario) y `docker login ghcr.io`; `export TICKETFLOW_IMAGE=ghcr.io/alhucave/ticketflow:0.1.0; docker-compose pull app; docker-compose up -d --wait --no-build; ./requests/demo.sh` (compra hasta SOLD), `docker-compose logs app | grep service` -> `0.1.0`; `docker-compose down -v`.
5. Paquete: enlazado al repo y **privado** (no cambiar la visibilidad sin decisión del dueño).
6. `gh release create v0.1.0 --verify-tag --title v0.1.0 --generate-notes` solo con el run en verde.
7. Actualizar DP-029/DP-040 si el run real desmiente algo; si algo falla: «Deshacer un release malo» del README (el tag se borra con `git push origin :refs/tags/v0.1.0`; las tags de imagen ya publicadas se borran con `delete:packages`, se corrige con `v0.1.1`).

## Decisiones de diseño (resumen)

- Scripts en `scripts/` (no inline en YAML): se ejecutan idénticos en local y en CI y se pueden probar; los gates se prueban offline en `init.sh` con un `gh` simulado, y contra la API real a mano.
- El gate acepta cualquier `verify` exitoso del commit (no solo el último) y exige commit en `main`: un squash deja las puntas de rama fuera de `main`, y es la forma real de publicar algo no verificado.
- `permissions: {}` global y permisos por job; `smoke` pide `contents: read` solo por el checkout del script.
- `latest` por flavor `auto`; el tag git sin patrón semver no dispara el workflow.
- Multi-arquitectura rechazada por regresión del inicio rápido (evidencia arriba), no por falta de viabilidad técnica.
- La versión del fuente sigue siendo `0.0.1-SNAPSHOT`; la del tag vive solo en la imagen (documentado en DP-040 y README).

## Salida de `./init.sh`

```
==> Validating feature_list.json
OK: 34 features, in_progress=['F-032']
==> Validating spec traceability and decisions register
OK: 34 features with origin, 40 decisions, 69 requirement rows
==> Validating CI runner images are pinned (no -latest labels)
OK: 7 runs-on entries, none uses a floating label
==> Testing the release gate scripts
(13 x "ok   ..." ; OK: release gate scripts behave as specified)
==> Building and verifying (tests + 90% coverage gate)
BUILD SUCCESSFUL in 1m 29s
==> init.sh OK
```

`INCLUDE_INTEGRATION=true ./init.sh` (con `DOCKER_HOST` de Colima y `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`): `BUILD SUCCESSFUL in 5m 27s`, `11 actionable tasks: 11 executed`, `==> init.sh OK`.

Limpieza: builder buildx `f032`, imágenes `ticketflow:f032-*`, directorios temporales en `$HOME/.f032-*` y el binario buildx (solo en el scratchpad) eliminados o confinados al scratchpad; ningún proceso de CPU en marcha.
