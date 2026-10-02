# Informe de implementacion: F-012 usecase-purchase-request

Rama `feature/F-012-usecase-purchase-request`. Estado en feature_list.json: `in_progress` (no marcada done).

## Archivos

Nuevos (main):
- `domain/port/OrderPlacementRepository` (`placeReservation`, `releaseReservation`).
- `domain/exception/IdempotencyKeyReusedException`, `OrderEnqueueFailedException` (`reservationReleased` flag).
- `infrastructure/persistence/DynamoDbOrderPlacementRepository`.
- `usecase/RequestPurchaseUseCase`, `RequestPurchaseCommand`, `RequestPurchaseResult`.

Modificados (main):
- `domain/model/OrderId`: `fromIdempotencyKey` (SHA-256 -> UUID version 5 layout, deterministic).
- `domain/model/OrderAuditEntry`: new optional `reason` (canonical 6-arg constructor; the old 5-arg one is kept, reason null).
- `DynamoDbOrderRepository`: audit item persists/reads `reason`; helpers made package-private for reuse.
- `infrastructure/config/UseCaseConfig`: `RequestPurchaseUseCase` bean, `ticketflow.reservation.ttl` (default `PT10M`).
- `docs/architecture.md` (purchase flow, idempotency, audit reason, known limitation).

Tests: `RequestPurchaseUseCaseTest` (mocks, all branches), `DynamoDbOrderPlacementRepositoryTest` (17, mocked client: request shape, every cancellation mapping, retries), `RequestPurchaseUseCaseIT` (12, real DynamoDB Local 3.3.1, fake in-memory queue), plus additions to `PortsContractTest`, `DomainExceptionsTest`, `ValueObjectsTest`, `OrderAuditEntryTest`, `UseCaseConfigTest`.

## Design

- Idempotency: `orderId = f(idempotencyKey)` only (not the payload), so the orders PK guard `attribute_not_exists(orderId)` enforces one order per key even concurrently. On `OrderAlreadyExistsException` the use case reads the order (consistent get) and compares eventId and quantity: match -> existing order returned (`replayed=true`, no reserve, no publish); mismatch -> `IdempotencyKeyReusedException`.
- `placeReservation`: ONE TransactWriteItems `[0] inventory update (attribute_exists AND available >= :qty, version+1, ALL_OLD on failure) , [1] order put (attribute_not_exists, ALL_OLD), [2] audit put AVAILABLE->RESERVED`. Mapping: order condition failed -> `OrderAlreadyExists` (takes precedence over inventory so retries and sold-out replays are recognised as replays); inventory condition failed with item -> `InsufficientInventory`; without item -> `EventNotFound`.
- `releaseReservation(order, expected, actor, reason, at)`: ONE TransactWriteItems `[0] order update (exists AND status = expected AND eventId AND quantity match) -> AVAILABLE, [1] audit put (with reason), [2] inventory update (source counter >= qty -> available)`. Supports RESERVED (reserved counter) and PENDING_CONFIRMATION (pendingConfirmation counter) so F-014/F-017 can reuse it. Second call -> `OrderStatusConflictException`, nothing changes. Order guards on eventId/quantity so a caller's stale copy cannot misdrive the inventory. Inventory-counter failure (corruption) -> `IllegalStateException`.
- Publish happens after the transaction. On failure: log, `releaseReservation`, then `OrderEnqueueFailedException(released=true, cause=publishError)`. If release also fails: log, `OrderEnqueueFailedException(released=false)` with the release error suppressed; never swallowed.
- Retries only for transient errors (reuses `DynamoDbOrderRepository.isTransient`: throttling, TransactionConflict-only cancellations); budget 10 retries, 20 ms to 1 s backoff with jitter, because all purchases of one event contend on one inventory item. Retrying after an ambiguous commit is safe (the order put is conditional).
- Audit "reason": the record had only `actor`; added an optional `reason` instead of encoding it in the actor string.
- Wiring: no `OrderQueuePublisher` bean exists until F-015, and the Spring context test would fail without one. `UseCaseConfig` uses `ObjectProvider.getIfAvailable` with a fallback publisher that always errors (purchases then compensate loudly rather than dropping messages). Remove the fallback in F-015.

## Flaws / caveats found (documented, not silently deviated)

1. Crash window: if the process dies between the transaction and the publish, the order stays RESERVED with no message; a retry with the same key returns the order but does not republish (deliberate: avoids queue amplification from malicious retries). It is reclaimed by expiry (F-017). Republishing on replay of a RESERVED order is a possible follow-up (consumer is idempotent).
2. A concurrent same-key loser may receive an orderId whose order later ends AVAILABLE if the winner's publish fails (it returned the order "as it stands").
3. The idempotency key is global (no per-client/user scoping; no auth yet): anyone who knows a key sees that order's id/status and a different payload yields 409-style error. Scope the derived id by principal in F-023.
4. Audit sort key is `<timestamp>#<random uuid>`: entries with the identical timestamp have no defined relative order (only matters with a fixed clock; I hit this in my own test and used an advancing clock there). Production timestamps from the real clock are distinct.
5. Hot item: transactions on one inventory item conflict under heavy parallel load; handled by retries (100 parallel requests passed repeatedly with the default budget) but throughput on a single event is bounded. Real DynamoDB may conflict more than DynamoDB Local.
6. A bug found and fixed while testing: compensation used `.map` on the release result, so an empty release would have silently completed; now `.thenReturn`.

## Acceptance coverage

- Returns orderId without waiting: `execute_purchase_returnsOrderIdReservesInventoryAndEnqueues` (IT), unit equivalent.
- Same key never second order/reservation: sequential, 50 parallel, different payload, replay when sold out (IT) + unit.
- Expiry now + 10 min (configurable, fixed Clock): unit and IT; custom TTL unit; config property.
- Publish failure compensates: IT (inventory back, order AVAILABLE, audit with reason, version 2) + unit (released and not-released variants).
- Also: 100 parallel distinct keys over capacity 30 -> exactly 30 orders, 70 `InsufficientInventory`, invariant holds, 30 published; double/concurrent release -> one winner, counters unchanged.

## Verification output

Both runs executed with feature F-012 in_progress.

`./init.sh`:
```
OK: 26 features, in_progress=['F-012']
BUILD SUCCESSFUL in 18s
==> init.sh OK
```
`INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST=unix://$HOME/.colima/default/docker.sock, TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock):
```
OK: 26 features, in_progress=['F-012']
==> Integration tests enabled (INCLUDE_INTEGRATION=true)
BUILD SUCCESSFUL in 36s
==> init.sh OK
```
Line coverage with integration: 781/786 (99%); plain run passed the 90% gate. `RequestPurchaseUseCaseIT` ran 5 consecutive times green after fixing the test's fixed-clock ordering issue (see caveat 4).

## Round 2 (CHANGES_REQUESTED fixes)

Review items addressed:
1. `replay()` no longer returns an AVAILABLE (compensated/expired) order as success. Option (a): new `IdempotentOrderNotActiveException` (domain.exception; key + orderId; meaning: the order of this key was released, retry with a new Idempotency-Key). Payload-mismatch check runs first, so different payload => `IdempotencyKeyReusedException`; same payload + AVAILABLE => `IdempotentOrderNotActiveException`; otherwise replay as before. No reservation, no publish, no release on this path.
2. `docs/architecture.md` (Flujo de compra) documents the consumed-key behaviour next to the known limitation.

Tests added: unit `execute_orderAlreadyExistsButReleased_...` and `execute_releasedOrderWithDifferentPayload_keyReusedTakesPrecedence`; `DomainExceptionsTest.idempotentOrderNotActive_created_exposesDetails`; IT `execute_retryAfterCompensation_sameKeyAndPayload_failsNotActiveWithoutReserving` (publish fails -> compensated; queue recovers; retry same key/payload -> typed exception; inventory 20/0 and version unchanged, invariant holds, nothing published, order AVAILABLE; different payload on the key still gives key-reused).

Non-blocking notes for later features (not implemented here): web layer F-023 must map `IdempotentOrderNotActiveException` to a non-202 error (e.g. 409) and not cancel the pipeline on client disconnect; F-015 must remove the always-error fallback publisher in UseCaseConfig; F-016 consumer must ignore messages whose order is not RESERVED.

Verification: `./init.sh` -> BUILD SUCCESSFUL, `==> init.sh OK`; `INCLUDE_INTEGRATION=true ./init.sh` (Colima env) -> BUILD SUCCESSFUL, `==> init.sh OK`.
