# Review — feature F-032 (first-release-ghcr)

**Veredicto:** APPROVED

Alcance revisado: rama `feature/F-032-first-release-ghcr` (1143c35, local). Todo se re-ejecutó de forma independiente; no se creó ningún tag, release ni push, no hubo `docker login` ni pull desde ghcr.io. Los criterios de aceptación 2 y 3 (tag `v0.1.0` real y prueba de la imagen publicada) quedan fuera de esta rama por diseño: los cumple la sesión principal tras el merge, y por eso F-032 sigue `in_progress` (correcto, no `done`).

## Criterios de aceptación
- Revisión de `release.yml` contra la realidad (mapeo de tags, permisos, labels, Dockerfile, versión en runtime): cubierto por ejecución independiente de `docker/metadata-action` fijado (SHA dc80280...), `scripts/test-release-scripts.sh`, `scripts/smoke-image.sh` y build con `--build-arg` — [x]
- Primer tag `v0.1.0` y run con digest/tags publicados: **diferido a la sesión principal** (no verificable sin publicar); procedimiento exacto en README «Publicar un release»; feature permanece `in_progress` — [x] (como se pidió, no se marca done)
- Verificar la imagen publicada con docker-compose: diferido igual; `TICKETFLOW_IMAGE` en `docker-compose.yml` y pasos documentados (incluye `gh auth refresh -s read:packages` a cargo del usuario) — [x] (diseño), pendiente el run real
- README/docs: cómo descargar y ejecutar, visibilidad privada, decisión del dueño — [x] (README «Usar la imagen publicada»)
- Registrado en `docs/decisions.md` (DP-040) y enlazado en `feature_list.json` — [x]

## Verificaciones independientes
- `actionlint` (Docker, `rhysd/actionlint:latest`) sobre los tres workflows: sin hallazgos, rc 0.
- `./init.sh --check-runners`: OK (7 runs-on, sin etiquetas flotantes).
- `./init.sh` completo: verde (`BUILD SUCCESSFUL in 1m 25s`, `==> init.sh OK`), incluye los 13 casos de `scripts/test-release-scripts.sh`.
- Gates contra la API real: commit verde de main (8434d85) -> `require-green-verify.sh` OK y `require-commit-on-main.sh` OK (identical); commit inicial sin CI (81872c3) -> falla con mensaje claro, rc 1; `refs/pull/27/head` (diverged) -> rechazado; SHA inexistente -> falla cerrado (404).
- Smoke sobre imagen local construida con `--build-arg APP_VERSION=0.1.0`: liveness 200 en 4 s, readiness 503 informado, `service.version=0.1.0` leído de los logs del contenedor. Control negativo (versión esperada 9.9.9): `SMOKE FAILED ... found '0.1.0'`, imprime logs, no quedan contenedores `ticketflow-smoke*`. Build sin build-arg: `service.version=0.0.1-SNAPSHOT`. Label `org.opencontainers.image.source` presente en la imagen etiquetada con las labels de la acción.
- `docker-compose config`: `image: ticketflow:local` por defecto (sin cambios de comportamiento).
- metadata-action fijado, ejecutado en local (servidor de API simulado): `v0.1.0` -> `0.1.0`, `0.1`, `latest` (sin duplicados, `version=0.1.0`, `image.source=https://github.com/alhucave/ticketflow`); `v0.1.1` -> `0.1.1`, `0.1`, `latest`; `v1.2.3` -> `1.2.3`, `1.2`, `latest`; `v0.2.0-rc.1` -> solo `0.2.0-rc.1`.
- Todas las acciones fijadas por SHA (8): `gh api repos/<o>/<r>/commits/<tag>` coincide en cada una (checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1, trivy-action v0.36.0, build-push v7.4.0, login v4.6.0, metadata v6.2.0, setup-buildx v4.4.1); ningún `uses:` sin SHA.
- Permisos: `permissions: {}` global; `gate` contents+checks read; `publish` contents read + packages write (único con escritura); `smoke` contents + packages read. Mínimo privilegio correcto.
- Rechazo multi-arch: (a) la afirmación del constructor clásico se reprodujo (`FROM --platform=$BUILDPLATFORM` con `docker build` sin buildx -> `failed to parse platform : "" is an invalid OS component`; esta máquina no tiene `docker buildx`); (b) los digests base fijados tienen amd64 y arm64/v8 (`docker manifest inspect`; el distroless además s390x/ppc64le/riscv64); (c) los tiempos de QEMU (4 min 55 s frente a 1 min 37 s) no se repitieron, pero son plausibles para Gradle+JDK emulado y el razonamiento no depende de la cifra exacta. Razonamiento aceptado y honestamente documentado, con camino para activar arm64.
- Nada publicado: `git tag` vacío, `git ls-remote --tags origin` vacío, `gh release list` vacío, `gh run list --workflow release.yml` vacío. (`gh api /user/packages` no se pudo consultar: el token carece de `read:packages`; el resto de evidencias bastan y no hay ningún run de Release.)
- `git diff main --stat`: solo `.github/workflows/release.yml`, `Dockerfile`, `build.gradle.kts` (propiedad `appVersion`, necesaria), `docker-compose.yml`, `init.sh` (+ test de scripts), `scripts/`, README, `docs/`, y bookkeeping (`feature_list.json`, `progress/`). Sin cambios ajenos.
- `feature_list.json`: F-032 `in_progress`, `origin: own`, `decisions: ["DP-040"]`.

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` verde, ejecutado por el revisor
- C2: [x] sin cambios en código de dominio/usecase/infrastructure Java
- C3: [x] cada criterio verificable en esta rama tiene prueba/evidencia reproducible (test offline de gates con control negativo, smoke con control negativo); los criterios 2-3 son del run real, diferidos explícitamente
- C4: [x] sin código de producción afectado
- C5: [x] n/a
- C6: [x] n/a
- C7: [x] scripts, workflow y comentarios en inglés (docs en español, como el resto del repo)
- C8: [x] sin secretos; solo `GITHUB_TOKEN`/`secrets.GITHUB_TOKEN`
- C9: [x] cobertura ≥ 90 % (barrera de `./init.sh` en verde; sin cambios de código)
- C10: [x] scope acotado al acceptance
- C11: [x] README, DP-029 (actualizada), DP-040, verification.md coherentes con el código y los comandos
- C12: [x] n/a (no hay caso de uso que combine puertos)
- C13: [x] DP-040 registrada, enlazada, `origin: own` honesto

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. README «Publicar un release», paso 2: `gh run list --workflow release.yml --limit 1` justo tras `git push` puede devolver vacío o un run anterior (carrera de unos segundos); conviene esperar unos segundos o filtrar por `--branch v0.1.0`.
2. El job `smoke` falla tras publicar: la imagen ya está en el registro. El README lo trata como "release malo", correcto y documentado.
3. La verdad del push a ghcr desde repo privado, el digest y el smoke en el runner solo las demuestra el run real; la sesión principal debe seguir la lista de comprobación del README y actualizar DP-029/DP-040 si algo la desmiente.
4. Tras el run real, F-032 solo puede pasar a `done` cuando se cumplan los criterios 2 y 3.
