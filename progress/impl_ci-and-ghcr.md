# Implementación F-003 — ci-and-ghcr

Rama: `feature/F-003-ci-and-ghcr` (sin push). Estado en feature_list.json: `in_progress`.

## Archivos
- Nuevos: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `Dockerfile`, `.dockerignore`
- Modificados: `init.sh`, `docs/verification.md`, `README.md`, `feature_list.json` (F-003 -> in_progress), `progress/current.md`

## Decisiones
- CI: `pull_request` y `push` a `main`; `permissions: contents: read`; concurrency por workflow+ref con cancel-in-progress; Temurin 25 con `cache: gradle`; `INCLUDE_INTEGRATION=true` a nivel de job. Artefacto `reports` (`if: always()`) con `build/reports/jacoco`, `build/reports/tests`, `build/test-results`. El runner ubuntu-latest trae Docker para Testcontainers.
- `init.sh`: `INCLUDE_INTEGRATION=true` agrega `-PincludeIntegration` (propiedad usada en `tasks.test` de build.gradle.kts). Sin la variable, comportamiento local intacto. Se usa el idioma `${arr[@]+"${arr[@]}"}` porque con `set -u` un array vacío falla en bash 3.2 (macOS); se detectó y corrigió.
- Release: tags `v*`; permisos globales `contents: read`, `packages: write` solo en el job; login con `GITHUB_TOKEN`; imagen `ghcr.io/alhucave/ticketflow` (minúsculas); tags semver `{{version}}`, `{{major}}.{{minor}}` y `latest`. No se usó setup-buildx (driver docker por defecto basta). Release no ejecuta tests (se asume CI verde sobre el commit); el Dockerfile usa `-x test`.
- Dockerfile no existía: se creó multi-stage mínimo (temurin:25-jdk -> bootJar; temurin:25-jre, usuario no root, puerto 8080). Se excluye el `-plain.jar` al copiar. No se pudo construir la imagen (sin Docker); `bootJar` se verificó localmente.
- Versiones verificadas con `gh api` (latest release) y SHA resuelto por `commits/<tag>`:
  - actions/checkout v7.0.1 = 3d3c42e5aac5ba805825da76410c181273ba90b1
  - actions/setup-java v6.0.1 = de7274f081f381c8f8158605e0321c36c376e2e6
  - actions/upload-artifact v7.0.1 = 043fb46d1a93c77aae656e7c1c64a875d1fc6a0a
  - docker/login-action v4.6.0 = dbcb813823bdd20940b903addbd779551569679f
  - docker/metadata-action v6.2.0 = dc802804100637a589fabce1cb79ff13a1411302
  - docker/build-push-action v7.4.0 = c3c9e263c25d99ce0380d002d59b67737d91b0dc

## Verificación
- YAML de ambos workflows parseado con Ruby (`ci ok`, `release ok`); actionlint no disponible; los workflows no pueden ejecutarse localmente.
- `./gradlew bootJar -x test` OK.
- `./init.sh` -> BUILD SUCCESSFUL, `==> init.sh OK`, exit 0.
- `INCLUDE_INTEGRATION=true ./init.sh` -> imprime "Integration tests enabled", BUILD SUCCESSFUL (aún no hay tests de integración).

## Pendientes / notas
- Ejecución real de workflows e imagen Docker solo comprobables tras push/tag. Los paquetes ghcr nuevos son privados por defecto: hay que hacerlo público manualmente si se desea.
