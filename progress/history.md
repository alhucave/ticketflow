# Historial (append-only)

- 2026-10-02: Arnés creado (AGENTS.md, docs/, CHECKPOINTS.md, init.sh, feature_list.json).
- 2026-10-02: F-001 gradle-bootstrap — APPROVED (progress/review_gradle-bootstrap.md). Spring Boot 4.1.1, Gradle 9.8.0, Java 25, JaCoCo 90% gate. Pending for F-003: pass -PincludeIntegration in CI.
- 2026-10-02: F-003 ci-and-ghcr — APPROVED (progress/review_ci-and-ghcr.md). ci.yml (init.sh con INCLUDE_INTEGRATION=true) y release.yml (ghcr.io en tags v*), acciones fijadas por SHA. Workflows no ejecutados localmente: se validan en el primer PR/tag.
- 2026-10-02: F-002 docker-compose-infra — APPROVED (progress/review_docker-compose-infra.md). docker-compose con DynamoDB Local 3.3.1 y LocalStack 4.14.0 (2026.x exige auth token), colas orders + orders-dlq, contenedor non-root. Verificado en vivo con Colima.
- 2026-10-02: F-004 domain-ticket-state-machine — APPROVED (progress/review_domain-ticket-state-machine.md). TicketStatus con matriz 5x5 de transiciones, InvalidStateTransitionException. Primera feature con código real: gate JaCoCo 90% efectivo. main protegida (check verify).
