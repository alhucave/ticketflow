# Review — feature F-003 (ci-and-ghcr)

**Veredicto:** APPROVED

## Criterios de aceptación
(Feature de infraestructura: la prueba es verificación estática e independiente de los workflows; no hay test unitario aplicable.)
- CI runs ./init.sh on pull_request and push to main: ci.yml `on: pull_request` + `push: branches [main]`, step `run: ./init.sh`; YAML parsea OK; `INCLUDE_INTEGRATION: "true"` en env del job y `init.sh` lo traduce a `-PincludeIntegration` (build.gradle.kts:41) — [x]
- Coverage report uploaded as workflow artifact: upload-artifact con `if: always()`, path `build/reports/jacoco` (existe tras init.sh) — [x]
- release.yml publishes to ghcr.io on v* tags using GITHUB_TOKEN: tags `v*`, login-action con `secrets.GITHUB_TOKEN`, imagen `ghcr.io/alhucave/ticketflow` (minúsculas) — [x]
- Pin action versions and minimal permissions: las 6 actions fijadas por SHA completo; `contents: read` global; `packages: write` solo en el job de release.yml (grep confirma única aparición) — [x]

## Verificaciones independientes
- SHAs vs `gh api repos/<repo>/commits/<tag>`: los 6 coinciden exactamente (checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1, login v4.6.0, metadata v6.2.0, build-push v7.4.0).
- YAML válido (Ruby YAML) en ambos archivos.
- Sin secretos: solo `secrets.GITHUB_TOKEN`; Dockerfile sin credenciales.
- `./init.sh` -> BUILD SUCCESSFUL, `init.sh OK`.
- `INCLUDE_INTEGRATION=true ./init.sh` -> imprime "Integration tests enabled", BUILD SUCCESSFUL. Docker NO disponible en esta máquina y aún no existen tests de integración, así que no se ejercitó Testcontainers.
- Imagen Docker no construida (sin Docker); no verificable aquí.

## Checkpoints (CHECKPOINTS.md)
- C1: [x]
- C2: [x] (sin código de aplicación tocado)
- C3: [x] (criterios verificados estáticamente; no aplica test de código)
- C4: [x] n/a
- C5: [x] n/a
- C6: [x] n/a
- C7: [x] (sin Lombok/@Autowired; nombres en inglés)
- C8: [x]
- C9: [x] (gate JaCoCo pasa en init.sh)
- C10: [x] (Dockerfile/.dockerignore son necesarios para publicar la imagen; init.sh y docs acordes)
- C11: [x] README y docs/verification.md actualizados

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- release.yml no ejecuta tests; depende de CI verde sobre el commit del tag (documentado por el implementer).
- Dockerfile y workflow no se ejecutaron; se validarán con el primer push/tag. Paquete ghcr nuevo es privado por defecto.
