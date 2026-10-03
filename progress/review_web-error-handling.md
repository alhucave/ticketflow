# Review — feature F-020 (web-error-handling)

**Veredicto:** APPROVED

## Criterios de aceptación
- Each domain exception maps to the documented status: ErrorHandlingWebTest (parameterised per mapping: status, type, title, detail, instance, correlationId, headers, content type) + ErrorHandlingEndToEndIT (real DynamoDB Local + LocalStack) — [x]. README catalogue checked against live behaviour (404/405+Allow/406/415/413/400/500); 409 vs 503 decision documented in README, architecture.md and handler javadoc, consistent with code (concurrent-modification = 409 + Retry-After: 1; 503 only order-enqueue-failed). 429 + Retry-After and 410 covered in WebTest.
- 500 never exposes stack traces/internal messages: WebTest no-leak assertions + live test with DynamoDB stopped: fixed body, stack trace only in server log — [x]
- Correlation id in error responses and logs: WebTest, CorrelationMdcIsolationTest (120 concurrent requests, thread hops, mutation-checked), IT (SQS attribute read from the real queue) — [x]

## Checkpoints
- C1: [x] `./init.sh` OK and `INCLUDE_INTEGRATION=true ./init.sh` OK (both run by me, colima).
- C2: [x] domain/usecase have no Spring/AWS imports (grep clean); new code only in infrastructure.
- C3: [x]
- C4: [x] no `.block()` / `Thread.sleep` in src/main.
- C5: [x] N/A (no inventory writes changed).
- C6: [x] N/A.
- C7: [x] English code/comments, no Lombok, no field @Autowired.
- C8: [x] no secrets.
- C9: [x] coverage gate passed (jacocoTestCoverageVerification green; impl reports 99.0%).
- C10: [x] scope = error handling + correlation id; feature_list only moves F-020 to in_progress (not done).
- C11: [x] README, docs/architecture.md updated; catalogue matches behaviour.
- C12: [x] ErrorHandlingEndToEndIT runs the full Boot context against real DynamoDB Local + LocalStack SQS (404 domain, 409, 400, 413, 405, 415, correlation id forwarded to a real SQS message, CRLF injection). The feature adds no new use case combining ports; the error layer only reads exceptions the existing adapters already throw, and I confirmed the adapter->exception mappings (EventNotFound, OrderNotFound, EventAlreadyExists, OrderEnqueueFailed) are what the handler maps.

## Adversarial testing (real docker-compose, up --build / down -v)
- Unknown route, /error, /actuator/env, HEAD on unknown, TRACE, wrong method (Allow present), Accept text/html|xml (406), Content-Type xml/bogus (415), deeply nested JSON, null fields, bad types, 3 MB body (413): all application/problem+json, no Whitelabel, no trace/path/error/timestamp, no class names, X-Correlation-Id header present.
- Correlation id: empty, non-ASCII, 65 chars -> replaced by UUID; duplicated header -> first used (valid); CRLF / VT+ESC / bare LF injection -> rejected by Netty before the app (400), nothing logged from the value.
- 150 concurrent requests incl. client-cancelled (10 ms timeout): each log line carried its own id; no `[]` empty ids on request-handled lines; scheduler and SQS consumer threads log with empty `[]` (no leak). With DynamoDB down: 500 fixed body, ERROR log with stack and id; next request carries its own id.
- Publisher reads the same `correlationId` Reactor context key; verified by IT.

## Observaciones no bloqueantes
1. Container-level rejections (invalid percent-encoding such as `/%zz` or `?x=%%`, illegal header characters) return `400` with an empty body and no X-Correlation-Id: Netty rejects them before any WebFilter. Inherent to WebFlux/Netty, no information leaks; the README says id is on "todas las respuestas", which is not literally true for these. Consider a one-line caveat.
2. 404 `detail` for event/order not found echoes the path id (`Event not found: ../../etc`), because the domain exception message is used verbatim. It is JSON-escaped and problem+json, so low risk, but it contradicts the stated design goal of not reflecting user input (impl report says only the request path is not reflected). A fixed message would be tighter.
3. DynamoDB outage surfaces as 500 not 503 (noted by implementer as follow-up); fine for this scope.
