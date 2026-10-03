# Estado actual

Feature en curso: F-017 — reservation-expiration-job
Plan:
- Alinear frontera de expiracion a `expiresAt <= now` (query GSI, docs, Javadocs, tests).
- `ReleaseExpiredReservationsUseCase` (usecase, sin framework): busca vencidas y libera cada una con `OrderPlacementRepository.releaseReservation`; errores contenidos, conflicto benigno, concurrencia y tope por barrido configurables.
- `ReservationExpirationScheduler` (SmartLifecycle, infrastructure.scheduler) + `ExpirationProperties` + config condicionada por `ticketflow.expiration.enabled`.
- Tests unitarios (StepVerifier/virtual time) y IT con adaptadores reales (reloj mutable, barridos concurrentes, carrera con ProcessOrderUseCase, frontera).
- README, docker-compose, verificacion con ./init.sh (ambas variantes) y docker-compose real.
