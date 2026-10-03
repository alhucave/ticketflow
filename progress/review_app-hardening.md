# Review — feature F-023 part 1 (app-hardening)

**Veredicto:** APPROVED

Branch feature/F-023a-app-hardening (2601584 on cb39d05). F-023 stays `in_progress` in feature_list.json; part 2 (CI scans, dependabot, compose hardening, docs/security.md) untouched and out of scope.

## Execution (run independently by the reviewer)
- `./init.sh`: green (BUILD SUCCESSFUL, 90% coverage gate OK).
- `INCLUDE_INTEGRATION=true ./init.sh` (Colima env vars): green (BUILD SUCCESSFUL in 3m 8s, init.sh OK).

## Scope items
1. Rate limiting — OK. `ClientRateLimiter` uses Caffeine `maximumSize` + `expireAfterAccess` (TTL never below time-to-full), synchronous executor, injected ticker; tests cover bound/eviction. `ClientAddressResolver` ignores XFF unless `trust-forwarded-for=true` (default false); then uses the LAST entry, strict literal IPv4/IPv6 (no DNS, `%zone` and hostnames rejected, falls back to socket); canonicalised via InetAddress. Failed admin-key limiter checks `blockedFor` before comparing the key, charges only wrong/missing key, never creates entries for unknown clients on check. `Retry-After` is derived from the bucket (`(1-tokens)/refill`). Route matching uses the same PathPattern as routing (no bypass via encoding/params found). Filter order +15 (after correlation/headers, before admin key and body read).
2. Input limits — OK. `spring.http.codecs.max-in-memory-size=32KB` -> 413 (IT). `ticketflow.orders.max-quantity` configurable, validated at startup, tested (unit + IT with max-quantity=4). Idempotency-Key 16-128 in web layer only (domain unchanged so old orders stay readable); README curl examples (19 chars), ITs (`key-`+UUID) updated. `PathIds` validates all 5 entry points; invalid -> fixed-text 404, input never echoed (tested incl. hostile ids).
3. Retry safety — OK. `RequestPurchaseUseCase.replay` republishes RESERVED best effort (errors swallowed, logs class only, never releases); later states do not republish. `Detached` (`cache()` + pinned context) wraps `requestPurchase.execute` and `issueComplimentary.execute`; cancel tests: unit (`DetachedTest`) and IT with real DynamoDB+SQS (message appears exactly once; failure variant compensates, available == capacity).
4. Generation tokens — OK in both `SqsOrderConsumer` and `ReservationExpirationScheduler` (`running && generation == mine` in repeat predicate and in scheduler's cycle filter); restart-during-drain tests in unit and IT (consumer restarted 3 times, scheduler restart).
5. 503 — OK. `TransientFailures` is a `case ... when` after every business and `ErrorResponse` mapping, before default; TransactionCanceled is transient only when no ConditionalCheckFailed reason (`DynamoDbOrderRepository.isTransient`); ConditionalCheckFailed and AWS 4xx remain 500; fixed text, Retry-After 5, class-only WARN log. Business conflicts untouched (tested negative cases). Netty-level 400 documented in README:268.
6. Security headers / secrets — OK. `SecurityHeadersWebFilter` sets six headers at start and in `beforeCommit`; test covers success, 404, 405, 415, 400, 429, 503, 500 and actuator in IT. `toString` masked in `DynamoDbProperties`, `SqsProperties`, `AdminKeyGuard`/`AdminKeyWebFilter`; `SecretMaskingTest`. Other `@ConfigurationProperties` (RateLimit, SqsConsumer, Expiration) hold no secrets. Static AWS creds only when both access key and secret are set (`hasStaticCredentials`). No credential logging found.

## Checkpoints (CHECKPOINTS.md)
- C1: [x] both init.sh runs green, executed by reviewer.
- C2: [x] domain has no Spring/AWS/Caffeine imports; new code is all in infrastructure (+ a small usecase change).
- C3: [x] each acceptance-relevant item for this part (429, max quantity) and every scope item has a behavioural test (see above); no empty tests seen.
- C4: [x] no `.block()` or `Thread.sleep` in src/main; none in src/test either (Thread.sleep grep empty).
- C5: [x] inventory changes untouched (still conditional transactions); republish does not write inventory.
- C6: [x] no state-transition changes; replay republish is a read + publish only.
- C7: [x] English code/comments, no Lombok, no field @Autowired.
- C8: [x] no secrets; masked toString; compose-log check reported 0 matches.
- C9: [x] gate passes (90%); reported 99.4%.
- C10: [x] scope limited to part 1; only new dependency is Caffeine (BOM-managed). Part 2 items untouched.
- C11: [x] README and docs/architecture.md updated (rate limit, limits, 503, Netty 400, replay, Detached); progress/current.md updated.
- C12: [x] Verified explicitly. Real-adapter (Testcontainers DynamoDB + SQS) ITs exist for every new port-combining behaviour: `HardeningEndToEndIT` (replay of RESERVED with real queue, cancellation with real DynamoDB+SQS exactly-once publication and compensation variant, throttled real DynamoDB client -> 503, consumer/scheduler restarts, headers), `RateLimitEndToEndIT` (429 with nothing reserved for the rejected request; admin brute force), plus `RequestPurchaseUseCaseIT` updated for the new replay behaviour. Adapter compatibility checked: republish calls `queue.publish(existing)` (read-only on repository, no `save`/`create` contract involved); `Detached` does not alter `placeReservation`/`releaseReservation` transactional contracts; `isTransient` correctly excludes conditional-check cancellations so a business conflict cannot be reclassified as 503.

## Notes
- "Part of #23": the implementer report states commits/PRs must say "Part of #23", never "Closes". Commit 2601584 message says "(F-023 part 1)"; the PR body must carry "Part of #23" (reviewer cannot verify the PR yet).

## Observations (non-blocking)
1. IPv6 clients are keyed per full address: an attacker owning a /64 has effectively unlimited identities (limiter bypass and eviction churn of others' buckets). Same class of limitation the README already declares (edge layer/WAF, F-026); consider mentioning /64 explicitly in docs/security.md (part 2), or bucketing IPv6 by /64.
2. Per-instance limiter state and shared-bucket-when-behind-a-proxy-without-trust-flag are documented; keep them in docs/security.md.
3. Concurrent replays of a RESERVED order may publish N duplicate messages (documented; consumer idempotent).
4. Idempotency-Key per principal (F-012 note) is explicitly deferred to part 2/docs; F-023 remains in_progress so the note is not lost.
5. Admin-failure lockout is per client address: an attacker sharing the address (e.g. behind a NAT or untrusted proxy) can lock the legitimate admin out for ~20 s per token; acceptable, document it.
