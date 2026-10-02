# Historial (append-only)

- 2026-10-02: Arnés creado (AGENTS.md, docs/, CHECKPOINTS.md, init.sh, feature_list.json).
- 2026-10-02: F-001 gradle-bootstrap — APPROVED (progress/review_gradle-bootstrap.md). Spring Boot 4.1.1, Gradle 9.8.0, Java 25, JaCoCo 90% gate. Pending for F-003: pass -PincludeIntegration in CI.
- 2026-10-02: F-003 ci-and-ghcr — APPROVED (progress/review_ci-and-ghcr.md). ci.yml (init.sh con INCLUDE_INTEGRATION=true) y release.yml (ghcr.io en tags v*), acciones fijadas por SHA. Workflows no ejecutados localmente: se validan en el primer PR/tag.
