# Estado actual

Feature en curso: F-004 — domain-ticket-state-machine
Plan:
- Crear `TicketStatus` (enum) con `isFinal`, `isSale`, `canTransitionTo` y `transitionTo` (switch con pattern matching)
- Crear `InvalidStateTransitionException` en `domain.exception`
- Tests: matriz completa 5x5, flags y excepcion
- Verificar con `./init.sh` (gate JaCoCo 90%)
Ultimas completadas: F-001, F-002, F-003 (APPROVED)
