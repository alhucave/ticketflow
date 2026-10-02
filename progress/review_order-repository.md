# Review — feature F-009 (order-repository)

**Veredicto:** APPROVED

Verification run independently: `./init.sh` green (jacoco 90% OK); `INCLUDE_INTEGRATION=true ./init.sh` (Colima) green. DynamoDbOrderRepositoryIT: 15 tests, 0 failures/errors/skips; DynamoDbOrderRepositoryTest: 22 tests, 0 failures.

## Criterios de aceptación
- Status transition uses a condition on the expected current status: DynamoDbOrderRepository.updateStatus (`attribute_exists(#id) AND #status = :expected`); IT `transition_expectedStatusMatches_...`, `transition_staleExpectedStatus_failsWithConflictAndWritesNothing`, `transition_unknownOrder_...`, `transition_invalidStateMachineMove_isRejectedBeforeWriting`, plus parallel tests — [x]
- Every transition appends an audit entry (from, to, timestamp, actor): same `TransactWriteItems` (Update + Put), so atomic; the stale-status IT asserts no audit written; `auditTrail_*` and `audit_sortKey_isTimestampHashUuid` — [x]
- findByIdempotencyKey returns the existing order: GSI query; IT `findByIdempotencyKey_existing_returnsOrder` — [x]
- findExpired returns only RESERVED/PENDING_CONFIRMATION with expiresAt < now: one GSI query per status with strict `<`; IT `findExpiredReservations_onlyExpirableStatusesBeforeNow` and `..._afterTransitionToSold_orderNoLongerListed` — [x]

## Puntos verificados
- Conditional transition + TicketStatus rules: `new OrderAuditEntry(...)` runs `from.canTransitionTo(to)` before any I/O (invalid moves rejected; SOLD/COMPLIMENTARY final). OK.
- Atomic transaction with audit: single TransactWriteItems; `ReturnValuesOnConditionCheckFailure=ALL_OLD` distinguishes NotFound vs real-status conflict. OK.
- Unique audit SK: `<9-decimal ISO>#<uuid>`; IT covers same-instant entries and mixed precisions ordering. Note the UUID is generated in `auditItem`, so the returned entry does not expose its SK (fine).
- Create overwrite guard: `save` uses `attribute_not_exists(orderId)` -> OrderAlreadyExistsException; IT verifies original not overwritten.
- findExpired: fixed-width timestamp format makes lexicographic comparison correct (the format change is documented in architecture.md and javadoc, and no earlier feature stores this field). Pagination handled via `expand`.
- Retry only transient: `isTransient` covers throttling, 5xx, TransactionInProgress, and TransactionCanceled only when no ConditionalCheckFailed reason and some transient code. Business failures are never retried. Unit tests cover this.
- No `.block()`/`Thread.sleep` in src/main (grep clean).
- Domain purity: no Spring/AWS imports under `domain` (grep clean). New exceptions are plain RuntimeExceptions in `domain.exception`.
- Concurrency test meaningful: two-parallel and many-parallel IT against real DynamoDB (Testcontainers) assert exactly one winner and exactly one audit entry.

## Checkpoints (CHECKPOINTS.md)
- C1: [x]
- C2: [x]
- C3: [x]
- C4: [x]
- C5: [x] (no inventory change in this feature; order transition is conditional)
- C6: [x]
- C7: [x] (`@Autowired` is on the constructor only, same as F-008; no field injection, no Lombok)
- C8: [x]
- C9: [x] (jacoco verification passes at 90%)
- C10: [x] (port gains `transition` and two exceptions, which are needed for the acceptance; DynamoDbTables change is javadoc only)
- C11: [x] (architecture.md updated for SK format, fixed-width timestamps, transition semantics)

## Cambios requeridos
None.

## Observaciones no bloqueantes / notas para features posteriores
1. Idempotency uniqueness (F-012/F-010 use case): a GSI cannot enforce uniqueness. Two concurrent `save` calls with the same idempotencyKey and different orderIds can create two orders, and GSI reads are eventually consistent, so a retry right after the first POST may miss the order. The original spec requires idempotency; F-012 should derive orderId deterministically from the key, or write a key-reservation item with `attribute_not_exists`. The `save` guard only protects the orderId.
2. Missing failed/expired state: the spec lists five order states (including failed and expired), but TicketStatus has only AVAILABLE/RESERVED/PENDING_CONFIRMATION/SOLD/COMPLIMENTARY. With the current machine an expired or failed order can only go to AVAILABLE, which loses the reason and is ambiguous for "order status queryable in any of the five states". Needs a product/architecture decision before F-012 (mark failed) and F-017 (expire); likely a separate OrderStatus or added EXPIRED/FAILED. The adapter stores status as a string, so adding values is not a schema change, but the status+expiry GSI sweep and `EXPIRABLE` would need review.
3. Order id returned immediately (202) and 10-minute reservation: `Order` carries `reservationExpiresAt` and `save` is cheap, so the port supports both. Computing the 10-minute expiry belongs to the use case.
4. `findByIdempotencyKey` and `findExpiredReservations` are eventually consistent (GSI). The expiry sweeper tolerates that because `transition` is conditional on the expected status, but the use case should handle OrderStatusConflictException and OrderNotFoundException as benign.
5. `saveAuditEntry` remains a standalone non-conditional put in the port; use cases should prefer `transition` so status and audit stay atomic. Consider removing it or documenting this.
6. The `reservationExpiresAt` attribute stays on the order after it leaves RESERVED/PENDING_CONFIRMATION, which is harmless because the GSI partitions by status.
7. Inventory counter changes for pendingConfirmation are still missing from InventoryRepository (already tracked for F-014). The order transition and inventory counter updates are two separate writes, so a crash between them can leave them inconsistent. The use case or a reconciler must handle that.
