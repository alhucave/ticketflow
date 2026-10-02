# Estado actual

Feature en curso: F-003 — ci-and-ghcr (in_progress, pendiente de review)
Plan:
- ci.yml (PR + push main, ./init.sh con Java 25, INCLUDE_INTEGRATION=true, artefacto de reportes)
- init.sh: INCLUDE_INTEGRATION=true agrega -PincludeIntegration
- release.yml (tags v*, ghcr.io/alhucave/ticketflow) + Dockerfile multi-stage y .dockerignore
- Acciones fijadas por SHA; docs/README actualizados
Ultima completada: F-001 — gradle-bootstrap (APPROVED)
