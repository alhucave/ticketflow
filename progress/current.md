# Estado actual

Feature en curso: F-034 — fix-startup-race-missing-tables (implementada, pendiente de review; ver progress/impl_fix-startup-race.md)

Plan:
- Test de regresion de ventana de arranque (DynamoDB Local propio sin tablas) que falla hoy con 500.
- Clasificar ResourceNotFoundException como transitorio (503 + Retry-After) en TransientFailures y loguear la excepcion completa.
- Experimento real con docker-compose up (sin --wait), antes y despues.
- Docs: README, observability.md, DP-038.
