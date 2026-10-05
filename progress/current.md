# Estado actual

Feature en curso: F-032 — first-release-ghcr (issue #62). Estado: implementada, pendiente de review. NO se ha creado ningún tag, release ni push a ghcr.io (lo hace la sesión principal tras el merge).

Plan (cumplido):
- Revisar `release.yml` contra la realidad (metadata-action fijado ejecutado en local): quitar `type=raw,value=latest` (duplicaba `latest` y marcaba prelanzamientos), patrones de tag, permisos por job, setup-buildx fijado por SHA.
- `gate` (commit en `main` + check `verify` en verde), `publish` (versión del tag en la imagen), `smoke` (por digest, sin infraestructura): scripts en `scripts/`, probados en local.
- `ARG APP_VERSION` en el Dockerfile y `-PappVersion` en Gradle; compose y escaneo de seguridad intactos.
- Multi-arquitectura evaluada y rechazada (rompe el constructor clásico sin buildx): imagen solo `linux/amd64`.
- Documentación (README «Usar la imagen publicada» / «Publicar un release», DP-040, verification.md) y `./init.sh` en verde.

Informe: `progress/impl_first-release-ghcr.md`.
