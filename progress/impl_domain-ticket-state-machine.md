# Impl F-004 — domain-ticket-state-machine

## Archivos
- A: src/main/java/com/ticketflow/domain/model/TicketStatus.java
- A: src/main/java/com/ticketflow/domain/exception/InvalidStateTransitionException.java
- A: src/test/java/com/ticketflow/domain/model/TicketStatusTest.java
- M: feature_list.json (F-004 in_progress), progress/current.md

## Decisiones
- Enum con `canTransitionTo` / `transitionTo` (pura); switch exhaustivo con pattern matching sobre `this`, sin default, para que agregar un estado rompa la compilacion.
- Transiciones: AVAILABLE->RESERVED|COMPLIMENTARY, RESERVED->PENDING_CONFIRMATION|AVAILABLE, PENDING_CONFIRMATION->SOLD|AVAILABLE. Auto-transiciones (X->X) invalidas.
- Excepcion sin dependencias, expone `from()` y `to()`.
- Sealed/records no aplican aqui (enum es la forma natural).
- Tests: matriz 5x5 parametrizada (canTransitionTo y transitionTo), isFinal, isSale, estados finales sin salidas. Dominio sin imports Spring/AWS.

## init.sh
Salida: `OK: 26 features, in_progress=['F-004']` ... `BUILD SUCCESSFUL in 12s` ... `==> init.sh OK` (jacocoTestCoverageVerification paso).

Estado: pendiente de review; no marcada done.
