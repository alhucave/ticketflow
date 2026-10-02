# Review — feature F-012 (usecase-purchase-request)

**Veredicto:** CHANGES_REQUESTED

Reviewed at commit 17f3ae0. `./init.sh` green and `INCLUDE_INTEGRATION=true ./init.sh` green (run independently, Colima env as instructed).
The design is sound and the adapter/use-case contracts match; there is one functional defect on the retry path (item 1) plus one documentation gap (item 2).

## Criterios de aceptación
- Returns an order id without waiting for processing: RequestPurchaseUseCaseIT `execute_purchase_returnsOrderIdReservesInventoryAndEnqueues` + unit test — [x]
- Reservation expiry = now + 10 min, configurable, Clock-based: IT `execute_purchase_expiryIsNowPlusTenMinutesWithFixedClock`, unit custom TTL, UseCaseConfigTest (`ticketflow.reservation.ttl`, default PT10M) — [x]
- Same key never creates a second order or reservation (sequential, 50 parallel, sold-out replay): IT — [x]
- Same key + different payload => IdempotencyKeyReusedException (quantity and event): IT `execute_sameKeyDifferentPayload_failsAndDoesNotReserveAgain` — [x]
- No oversell: IT 100 parallel distinct keys over capacity 30 => exactly 30 orders, 70 InsufficientInventory, invariant holds — [x]
- Publish failure compensates atomically (inventory back, order AVAILABLE, audit with reason, version 2): IT `execute_publishFails_...` + unit (released / not released) — [x]
- Validation (null command fields, TTL <= 0, quantity/key via value objects): RequestPurchaseUseCaseTest, ValueObjectsTest — [x]
- Client retry after a failed enqueue: NOT covered and behaves wrongly (see item 1) — [ ]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] both runs green (independent).
- C2: [x] domain has no Spring/AWS imports (OrderId uses only java.security); usecase has no Spring.
- C3: [ ] retry-after-compensation path has no test (item 1); all other acceptance criteria are truly covered.
- C4: [x] no `.block()`/`Thread.sleep` in src/main; test `.block(WAIT)` only; no sleeps or polling in the new tests (IT is deterministic, fake queue, fixed clock).
- C5: [x] inventory moves only via conditional Update (`attribute_exists AND src >= :qty`, version+1) inside TransactWriteItems.
- C6: [x] AVAILABLE->RESERVED and RESERVED/PENDING_CONFIRMATION->AVAILABLE validated by OrderAuditEntry, audited in the same transaction; no new states.
- C7: [x] English, no Lombok, constructor injection (the existing `@Autowired` on constructors matches the sibling adapters; not field injection).
- C8: [x]
- C9: [x] jacoco gate passed (99% with integration per report; gate enforced by init.sh).
- C10: [ ] minor: `OrderAuditEntry.reason` and `DynamoDbOrderRepository` helper visibility changes are justified by the feature and docs; accepted. (Marked [x] in substance; no objection.)
- C11: [ ] architecture.md omits the retry-after-compensation consequence (item 2).
- C12: [x] RequestPurchaseUseCaseIT uses the real DynamoDbOrderPlacementRepository/Order/Inventory/Event adapters on DynamoDB Local. Contract check done by reading the adapter:
  - placement: one TransactWriteItems [inventory update, order put `attribute_not_exists(orderId)`, audit put]; order-exists takes precedence over inventory failure, which is exactly what the use case's `onErrorResume(OrderAlreadyExistsException)` replay relies on (sold-out replay IT proves it). Order built by the use case is RESERVED, matching the adapter's precondition.
  - the order is never visible without the reservation and vice versa (single tx); ambiguous-commit retry is safe because of the conditional put.
  - release: order update guarded by status, eventId, quantity + audit + inventory `reserved >= qty`; same tx, so double/concurrent release has one winner (IT) and inventory cannot be returned twice.
  - the use case calls release with the same Order instance it placed, so the eventId/quantity guards match.
  - Retry budget only for transient errors (TransactionConflict-only cancellations, throttling); business errors never retried.

## Cambios requeridos
1. Retry after a compensated publish failure is silently a no-op. After `compensate` the order stays in the table as AVAILABLE with the deterministic id. A client that got `OrderEnqueueFailedException` and retries with the same Idempotency-Key (the canonical use of the key) hits `OrderAlreadyExistsException`, `replay()` (RequestPurchaseUseCase.java, `replay`, ~line 95) finds eventId/quantity equal and returns `RequestPurchaseResult(status=AVAILABLE, replayed=true)` as a success: nothing is reserved, nothing is published, and the caller believes the order was accepted. Same for any key whose order was later released by expiry. Fix one of: (a) in `replay`, when `existing.status() == AVAILABLE` do not return success; fail with a typed domain exception (e.g. a purchase-released/failed error) so the web layer cannot answer 202; or (b) re-reserve on the same order (transition AVAILABLE->RESERVED in one tx) and publish. Whichever is chosen, add a unit test and an IT test: publish fails, then retry with same key and same payload, with the queue recovered, asserting the observable outcome and that inventory invariants hold. (Option (a) is the smaller change and sufficient for F-012.)
2. docs/architecture.md (Flujo de compra, "Limitación conocida" area): document the chosen behavior from item 1 (key is consumed by a released order) next to the existing limitation; the impl report's caveat 2 does not mention this case.

## Observaciones no bloqueantes
- Accepted documented flaws:
  - Crash/cancellation between the tx and publish leaves RESERVED without a message; no oversell and no inventory leak (reclaimed by expiry F-017, bounded by the 10 min TTL). Acceptable. Note that a cancelled subscription (client disconnect) during `execute` can hit the same window; the controller (F-023) should not cancel the pipeline on disconnect, or should subscribe detached.
  - UseCaseConfig always-error fallback publisher: acceptable as temporary (fails loudly, compensates, never drops). It must be removed in F-015; add that to the F-015 notes so it is not forgotten.
  - Global (unscoped) idempotency keys: acceptable until auth (F-023); the note is in the report.
  - Audit SK ordering with identical timestamps: only affects fixed clocks.
- If the publish actually succeeded but reported a timeout, the message exists for an order that is now AVAILABLE; the F-015/F-016 consumer must ignore messages whose order is not RESERVED (it already must be idempotent). Worth a line in F-016 notes.
- `releaseReservation` after an ambiguous commit followed by retry yields OrderStatusConflict, so `reservationReleased=false` may be reported although the release happened; harmless (the flag only means "not confirmed") but the log message "expiry sweep will reclaim it" could be misleading.
