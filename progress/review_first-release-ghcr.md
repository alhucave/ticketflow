# Review — feature F-032 (first-release-ghcr)

**Veredicto:** APPROVED

Rama `feature/F-032-first-release-ghcr`, commit de implementación `1143c35` sobre `main` `8434d85`. Todo lo de abajo se ejecutó de forma independiente (no se copió del informe). No se creó ningún tag, release, push ni imagen publicada; no hubo `docker login` ni pull desde ghcr.io. Solo consultas GET de la API de GitHub. Los criterios de aceptación 2 y 3 (tag real `v0.1.0` y prueba de la imagen publicada) los cumple la sesión principal tras el merge; por eso F-032 sigue `in_progress` (correcto).

Nota de proceso: al empezar esta revisión ya existía en la rama un commit `220ec34` con un `review_first-release-ghcr.md` (APPROVED) escrito por otra instancia de revisor. Este archivo lo reemplaza (se commitea encima, sin reescribir historia). Había además dos contenedores ajenos (`happy_hopper`, `dazzling_bhabha`) y el `ryuk` de Testcontainers que no creé y no toqué.

## Criterios de aceptación
- Revisión de `release.yml` contra la realidad (mapeo de tags, permisos, push con `GITHUB_TOKEN` desde repo privado, label `source`, build del Dockerfile, versión en runtime): cubierto por ejecución independiente de `docker/metadata-action` fijado, `scripts/test-release-scripts.sh` (con mutaciones), `scripts/smoke-image.sh`, build con `--build-arg APP_VERSION=0.1.0`, actionlint y shellcheck — [x]. Lo único no demostrable sin publicar (creación del paquete por `GITHUB_TOKEN`, digest real, smoke en el runner) está enumerado honestamente en el informe, DP-040 y README.
- Primer tag `v0.1.0` y run con digest y tags `0.1.0`, `0.1`, `latest`: DIFERIDO a la sesión principal por instrucción (no verificable sin publicar); procedimiento exacto en README «Publicar un release» y en el informe — [x] como entrega de esta rama; pendiente el run real
- Verificar la imagen publicada con docker-compose: DIFERIDO igual; la ruta `TICKETFLOW_IMAGE` + `--no-build` se probó localmente con la imagen construida a mano (versión `0.1.0` en logs, `demo.sh` completo) y el paso que requiere `gh auth refresh -s read:packages` del usuario está documentado — [x] (diseño), pendiente el run real
- README/docs: cómo descargar y ejecutar, visibilidad privada y que cambiarla es decisión del dueño: README «Usar la imagen publicada» — [x]
- Registrado en `docs/decisions.md` (DP-040, DP-029 actualizada) y enlazado en `feature_list.json` (`origin: own`, `decisions: ["DP-040"]`) — [x]

## Verificaciones independientes (resultados)

**1. Scope y nada publicado.** `git diff main --stat`: solo `.github/workflows/release.yml`, `Dockerfile`, `build.gradle.kts`, `docker-compose.yml`, `init.sh`, `scripts/` (4 archivos nuevos), `README.md`, `docs/decisions.md`, `docs/verification.md`, `feature_list.json`, `progress/`. Cero cambios bajo `src/`. `git ls-remote --tags origin`: vacío. `gh release list`: vacío. `~/.docker/config.json`: `"auths": {}` (sin entrada de ghcr.io).

**2. Workflow, línea a línea (`release.yml`).**
- Disparo (`release.yml:12-13`). Reglas de GitHub (workflow-syntax, filtros de patrón; leídas de la fuente de la documentación): `+` = «uno o más del carácter anterior», `[0-9]` es un rango válido, `*` = cero o más caracteres salvo `/`; la documentación usa el ejemplo `v[12].[0-9]+.[0-9]+` -> `v1.10.1`. El patrón debe casar el nombre completo. Por tanto: `v[0-9]+.[0-9]+.[0-9]+` casa `v0.1.0`, `v1.2.3`, `v10.20.30`; NO casa `v1`, `vfoo`, `v0.1`, `v0.1.0-rc.1`. `v[0-9]+.[0-9]+.[0-9]+-*` casa `v0.2.0-rc.1` y no casa `v0.1.0`; casa también `v1.2.3-` (prerelease vacío), caso límite sin importancia práctica (ver observación 3). Lo que mueve el patrón se lee de la documentación; no se pudo ejecutar el motor de GitHub sin empujar un tag.
- `permissions: {}` en el nivel de workflow (`:16`); `gate` `contents: read` + `checks: read` (`:33-35`); `publish` `contents: read` + `packages: write` (`:54-56`, el único con escritura); `smoke` `contents: read` + `packages: read` (`:119-121`). Mínimo privilegio correcto.
- `publish` `needs: gate` (`:51`); `smoke` `needs: publish` (`:116`).
- Inyección de expresiones: ningún `${{ }}` dentro de un `run:`. Los `run:` usan `$GITHUB_REPOSITORY`, `$GITHUB_SHA` y variables de `env:` (`TAGS`, `DIGEST`, `IMAGE_DIGEST`, `IMAGE_VERSION`). Las expresiones (`steps.meta.outputs.*`, `github.actor`, `secrets.GITHUB_TOKEN`) solo aparecen en `with:`/`env:`. Además el patrón de tag acota el nombre de entrada.
- `concurrency` `release-${{ github.ref }}` con `cancel-in-progress: false` (`:19-21`): dos pushes del mismo tag se serializan, nunca se cancela un publish a medias.
- Cableado digest/versión: `publish.outputs.digest = steps.build.outputs.digest` (build-push-action expone `digest`), `version = steps.meta.outputs.version`; `smoke` los lee de `needs.publish.outputs` por `env:` y falla si alguno está vacío (`:141-144`); la imagen se descarga por `IMAGE_NAME@digest`.
- Build-args `APP_VERSION=${{ steps.meta.outputs.version }}` (`:97-98`), usado por el Dockerfile y `build.gradle.kts` (`appVersion`); labels de metadata, que incluyen `org.opencontainers.image.source=https://github.com/alhucave/ticketflow` (reproducido, ver 4).
- Acciones (5 en este workflow, todas SHA completo de 40 hex con el tag en comentario; ninguna en `.github/workflows/` sin SHA). Cada SHA coincide con `gh api repos/<o>/<r>/commits/<tag> --jq .sha` y es el último release: checkout v7.0.1 `3d3c42e5...`, login-action v4.6.0 `dbcb8138...`, metadata-action v6.2.0 `dc802804...`, build-push-action v7.4.0 `c3c9e263...`, setup-buildx-action v4.4.1 (nueva) `f87e5991a6d7451dcb8d9637bfbc97413f497069`.
- actionlint 1.7.12 (`rhysd/actionlint:1.7.12`, versión confirmada con `-version`) sobre los tres workflows: rc 0, sin hallazgos. shellcheck (`koalaman/shellcheck:stable`) sobre `scripts/*.sh` e `init.sh`: rc 0.

**3. Gate y smoke.**
- `scripts/test-release-scripts.sh`: 13 casos `ok`, `OK: release gate scripts behave as specified`, 4.8 s.
- Mutaciones (todas restauradas con `git checkout --`, árbol limpio después):
  (a) `require-green-verify.sh`: aceptar `completed failure` -> el test falla (`[failed]: wanted fail, exit code 0`); aceptar cualquier `completed` -> falla igual; desactivar la espera (`-lt 0`) -> falla (`[running-then-green]: wanted pass, exit code 1`).
  (a5) quitar del filtro jq la condición `.name == "verify"`: los tests offline SIGUEN en verde. Es una limitación conocida y declarada (el `gh` simulado devuelve ya las líneas filtradas, así que el filtro jq y `app.slug` solo los prueba la consulta real); no bloqueante, ver observación 1.
  (b) `require-commit-on-main.sh`: aceptar `ahead`/`diverged` -> fallan 2 casos; `exit 1` -> `exit 0` -> fallan 3 casos (`ahead`, `diverged`, `api-error`).
  (c) `smoke-image.sh`: comparar la versión contra sí misma (`!= "$logged"`) -> con versión esperada `9.9.9` el smoke PASA (`Smoke test passed`), es decir, el comprobador de versión real es lo que hace fallar a la versión equivocada; restaurado.
- Gate contra commits reales (solo lectura, `GATE_WAIT_SECONDS=0`):
  - `require-green-verify.sh`: `8434d85` -> `OK ... (1 run(s) found)`, rc 0; commit inicial `81872c3` (sin CI) -> `no 'verify' check run exists`, rc 1; `c526b7e` -> `found: completed failure;`, rc 1; SHA `0000...` -> `Could not read the check runs ... 422`, rc 1 (falla cerrado).
  - `require-commit-on-main.sh`: `8434d85` -> `identical`, rc 0; `8309af3` (ancestro) -> `behind`, rc 0; `origin/feature/F-034-fix-startup-race` (`8fc2d06`, squash-merged) -> `not on main (compare status: diverged)`, rc 1; SHA `0000...` y `1143c35` (solo local, no empujado) -> `Not Found`, rc 1 (falla cerrado).

**4. Mapeo de tags.** Ejecuté `dist/index.cjs` de `docker/metadata-action` en el SHA fijado (`dc802804...`) en un contenedor `node:24-slim` (sin red, con un servidor de API local mínimo) con la configuración exacta del workflow (`type=semver,pattern={{version}}` y `{{major}}.{{minor}}`, sin `type=raw`):
- `v0.1.0` -> `0.1.0`, `0.1`, `latest` (sin duplicados); `version=0.1.0`; `org.opencontainers.image.source=https://github.com/alhucave/ticketflow`.
- `v0.1.1` -> `0.1.1`, `0.1`, `latest`. `v1.2.3` -> `1.2.3`, `1.2`, `latest` (no hay `1` a secas: no está en la config).
- `v0.2.0-rc.1` -> solo `0.2.0-rc.1`, sin `latest`.
Coincide con el informe y con DP-040.

**5. Versión y smoke de extremo a extremo (local).**
- `docker build --build-arg APP_VERSION=0.1.0 -t ticketflow:review-f032 .` OK (capas en caché, 4 s).
- `scripts/smoke-image.sh ticketflow:review-f032 0.1.0 90`: `OK: liveness 200 after 4s`, `readiness answers 503`, `OK: logs report service.version=0.1.0`, `Smoke test passed`, rc 0.
- Versión esperada `9.9.9`: rc 1, `SMOKE FAILED: expected service.version 9.9.9 in the logs, found '0.1.0'` más estado y logs del contenedor; `docker ps -a` sin contenedores `ticketflow-smoke*`.
- `docker-compose up --build -d --wait` por defecto: los logs dicen `0.0.1-SNAPSHOT` (`Starting TicketflowApplication v0.0.1-SNAPSHOT`); `./requests/demo.sh` termina `Demo finished OK` (compra hasta `sold: 3` en disponibilidad, `orders_sold_total 1.0`); `docker-compose down -v` limpio.
- `TICKETFLOW_IMAGE=ticketflow:review-f032 docker-compose up -d --wait --no-build`: el contenedor usa esa imagen (`docker inspect`), logs con versión `0.1.0`, `demo.sh` OK, `down -v` limpio. No se usó ninguna referencia ghcr.

**6. Build y tests.** `./init.sh`: rc 0, `BUILD SUCCESSFUL in 1m 26s`, `==> init.sh OK` (34 features, 40 decisiones, 69 filas de matriz, 7 runners fijados, 13 casos de gate). `INCLUDE_INTEGRATION=true ./init.sh`: rc 0, `BUILD SUCCESSFUL in 5m 52s`; suma de `build/test-results`: 976 tests, 0 skipped, 0 failures, 0 errors (igual que el baseline de `main`). Ejecutados una vez cada uno, en secuencia, sin otro Gradle en paralelo.

**7. Docs y decisiones.** DP-040 describe lo que hace el workflow (patrones de tag, tags de imagen, `gate` con permisos por job, `smoke` por digest, solo amd64, `setup-buildx`, `TICKETFLOW_IMAGE`) y coincide con `release.yml`, los scripts y mi ejecución. DP-029 ya no afirma «nunca se ha ejecutado» y apunta a DP-040 sin afirmar si hay imagen publicada. README «Usar la imagen publicada» (login con `gh auth refresh -s read:packages` + `docker login`, `docker-compose pull app` y `up -d --wait --no-build`) y «Publicar un release» (`git tag -a`, `git push origin v0.1.0`, `gh run watch ... --exit-status`, `gh release create --verify-tag --generate-notes`, rollback con `git push origin :refs/tags/...`, `gh release delete --cleanup-tag --yes`, `delete:packages` y la API `/user/packages/container/ticketflow/versions`) son sintácticamente correctos; los comandos que publican no se ejecutaron. «Solo amd64 (en Apple Silicon corre bajo emulación)» está dicho con honestidad en README y DP-040, con el camino para activar arm64. La afirmación de versionado (el fuente sigue `0.0.1-SNAPSHOT`, solo la imagen lleva la versión del tag) es exacta: confirmado con compose por defecto (`0.0.1-SNAPSHOT`) frente a la imagen con build-arg (`0.1.0`). `origin: own` es honesto: el enunciado no pide publicación ni versionado; ninguna fila de `docs/requirements.md` se ve afectada (búsqueda de «ghcr/release/imagen publicada»: solo coincide RF-6 «Liberación», no relacionado).

## Checkpoints (CHECKPOINTS.md)
- C1: [x] `./init.sh` verde, ejecutado por el revisor (plano y con `INCLUDE_INTEGRATION=true`, 976 tests, 0 fallos)
- C2: [x] sin cambios en Java (`src/` intacto); la regla de dependencias no se ve afectada
- C3: [x] cada criterio verificable en la rama tiene prueba que puede fallar (mutaciones a, b y c demostradas); los criterios 2 y 3 son del run real y están diferidos explícitamente
- C4: [x] sin código de producción afectado
- C5: [x] n/a (sin cambios de inventario)
- C6: [x] n/a (sin transiciones de estado)
- C7: [x] scripts, workflow y comentarios en inglés; docs en español
- C8: [x] sin secretos; solo `secrets.GITHUB_TOKEN`/`github.token`; el token no se imprime (`::error::` y logs no lo interpolan; `GH_TOKEN` solo en `env` del job `gate`)
- C9: [x] barrera de cobertura del 90 % de `./init.sh` en verde (sin cambios de código)
- C10: [x] cambios acotados a la feature; `build.gradle.kts` (propiedad `appVersion`) y `docker-compose.yml` (`TICKETFLOW_IMAGE`) son necesarios para el acceptance
- C11: [x] README, DP-029, DP-040 y `docs/verification.md` coinciden con workflow, scripts y comportamiento medido
- C12: [x] n/a (ningún caso de uso que combine puertos; el equivalente aquí, que gate y smoke funcionan, se probó con consultas reales y la imagen real)
- C13: [x] DP-040 registrada y enlazada, DP-029 actualizada, `origin: own` honesto, matriz de requisitos sin cambios necesarios; `./init.sh --check-registry` OK

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. `scripts/require-green-verify.sh:24-27` (filtro jq por `name` y `app.slug`) no está cubierto por los tests offline (el `gh` simulado ya devuelve líneas filtradas; mutación a5 sobrevive). Está declarado y se probó contra la API real; un test con JSON crudo de check-runs lo cerraría.
2. Carrera de arranque del gate: si el tag se empuja antes de que GitHub registre el check run `verify` del push a `main`, la respuesta es vacía y el gate falla de inmediato (la espera solo se activa con un run `queued/in_progress` ya visible, `require-green-verify.sh:36-40`). No publica nada (falla cerrado) y el README exige esperar el CI de `main` antes de etiquetar (paso 0), así que es aceptable; se podría reintentar unos 60 s cuando no hay ningún run. Además, la espera máxima es de 20 min y `verify` puede durar hasta 30 min por su `timeout-minutes`: un CI lento haría fallar el gate sin publicar; basta volver a empujar el tag o re-ejecutar el run.
3. `v[0-9]+.[0-9]+.[0-9]+-*` también casa `v1.2.3-` (y prelanzamientos no semver como `v1.2.3-a_b`); metadata-action no daría tags o daría error y nada se publicaría bien, pero el gate habría pasado. Caso límite sin impacto salvo error de tipeo.
4. Falta documentar qué hacer si GitHub rechaza la creación del paquete en el primer push (`publish` falla con 403/`denied`): el paquete no existe, no hay nada publicado y el tag se puede borrar y volver a empujar (`git push origin :refs/tags/v0.1.0`, corregir la causa: permiso de Actions «read and write» en Settings > Actions > General o crear el paquete con un PAT `write:packages` como secreto, y repetir con el mismo `v0.1.0`, ya que no quedó ninguna imagen). El README solo cubre el caso «ya publicada». El informe y DP-040 sí listan este riesgo como lo único que prueba el run real.
5. `smoke` fallido tras `publish` deja la imagen publicada pero sin verificar y el run en rojo (visible, no silencioso). El README lo trata como release malo (borrar tag y versiones del paquete, publicar `v0.1.1`): correcto. Si el fallo es solo del smoke (p. ej. login/pull por permisos de `packages: read` sobre un paquete recién creado), el operador debe reproducir `scripts/smoke-image.sh ghcr.io/alhucave/ticketflow@<digest> 0.1.0` en local tras `docker login` antes de decidir entre repetir el job (`gh run rerun --failed`) y publicar un parche.
6. README «Publicar un release», paso 2: `gh run list --workflow release.yml --limit 1` justo tras el `git push` puede devolver vacío o un run anterior (carrera de segundos); conviene esperar o filtrar con `--branch v0.1.0`.
7. Tras el run real, F-032 solo pasa a `done` cuando se cumplan los criterios 2 y 3; hay que actualizar DP-029/DP-040 si el run real desmiente algo (p. ej. la creación del paquete desde repo privado con `GITHUB_TOKEN`).
