# Review — feature F-035 (repository-is-public), revisión 2

**Veredicto:** APPROVED

## Criterios de aceptación
- Documentación coherente con el estado real del repositorio público (visibilidad, protección de `main`, reporte privado, secret scanning, aprobación de externos, permisos del token): comprobada contra la API de solo lectura — [x]
- La afirmación sin sustento «ejecuciones más rápidas» retirada: grep de «más rápid», «faster», «rápid» en README, AGENTS, SECURITY y docs sin restos; los «rápido» que quedan son legítimos (inicio rápido, validaciones rápidas, acceso rápido) — [x]
- Texto de sustitución honesto (README, docs/security.md, DP-029, DP-041): runners gratuitos y sin cupo «según la documentación de GitHub», efecto en la duración «no medido», ejecuciones de 8-11 min. Mis consultas a `gh run list` dan 467-651 s (7,8-10,9 min), coherente con «8-11 min» — [x]

## Contraste con la API (solo lectura, 2026-10-05)
- repo: `private: false`, `visibility: public`, `default_branch: main`; secret_scanning y push_protection `enabled`; dependabot_security_updates `disabled` (documentado así).
- branches/main/protection: contexts `["verify"]`, `strict: true`, `enforce_admins: false`, force-push y borrados `false`, sin `required_pull_request_reviews` (documentado como `null`/sin revisiones).
- private-vulnerability-reporting: `enabled: true`.
- fork-pr-contributor-approval: `all_external_contributors`.
- workflow permissions: `read`, `can_approve_pull_request_reviews: false`.
Todo coincide con README, SECURITY.md, docs/security.md y DP-041.

## Verificaciones
- Enlaces y anclas (script propio, sobre README, AGENTS, SECURITY, docs/*.md, progress/current.md e impl): 976 enlaces, 0 rotos.
- `./init.sh`: `BUILD SUCCESSFUL in 1m 28s`, `==> init.sh OK` (ejecutado una vez, en primer plano).
- `git diff main --stat` toca solo AGENTS.md, README.md, SECURITY.md, docs/ (aws, decisions, security, verification), feature_list.json, progress/ (current, impl). Sin código ni workflows.

## Checkpoints (CHECKPOINTS.md)
Sin cambios respecto a la revisión 1 en archivos de código; init.sh en verde, cambios limitados a documentación y registro, informe del implementer presente. Todos [x]. F-035 queda pendiente en feature_list.json, aceptable según lo indicado.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- Dependabot security updates sigue desactivado; está documentado con honestidad.
- La visibilidad del paquete ghcr debe comprobarse tras el primer release (ya está en la lista de comprobación).
