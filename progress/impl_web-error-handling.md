# F-020 web-error-handling: implementation report

## Files
New (main): `infrastructure/web/error/{CorrelationId,CorrelationIdWebFilter,CorrelationContextPropagation,ProblemWebExceptionHandler,RateLimitExceededException}.java`, `infrastructure/config/CorrelationConfig.java`.
Modified: `ApiExceptionHandler` (rewritten around one `translate(...)` switch; `problem(...)` is still the single builder), `application.yml` (`logging.pattern.correlation: "[%X{correlationId:-}] "`), `build.gradle.kts` (+ `io.micrometer:context-propagation`, BOM-managed), README (error catalogue + correlation id, Spanish), `docs/architecture.md`, `feature_list.json` (F-020 in_progress; NOT done), `progress/current.md`.
Tests: `web/error/ErrorProbeController` (test-only thrower), `ErrorHandlingWebTest` (49+ cases, @WebFluxTest with Boot's real error auto-config), `CorrelationMdcIsolationTest`, `ErrorHandlingEndToEndIT` (C12).

## Design
- One translator: `ApiExceptionHandler.translate(Throwable, correlationId)`. Used by the `@RestControllerAdvice` (`@ExceptionHandler(Throwable.class)`) and by `ProblemWebExceptionHandler` (`WebExceptionHandler`, order HIGHEST_PRECEDENCE+10, i.e. before Boot's `DefaultErrorWebExceptionHandler` at -1) for errors raised outside the dispatcher: unknown route, 405/406/415 at routing level, exceptions thrown by WebFilters (F-023 rate limiter). Verified unmatched path returns problem+json, no Whitelabel, no `path`/`trace`/`requestId`/`error`, and the requested path is not reflected.
- Shape: type `urn:ticketflow:problem:*`, title, status, detail, `instance` = `urn:ticketflow:request:<correlationId>` (deliberately NOT the request path: avoids reflecting user input), `correlationId`, `violations` for validation.
- Catch-all: any other Throwable (incl. Errors) -> 500 with fixed detail; real error logged at ERROR with stack trace and correlation id (MDC set explicitly in the handler as well, so it holds even if a signal arrives without propagated context). Framework 5xx (`ResponseStatusException` 5xx) treated the same. Handled 4xx log one INFO line `Request rejected: status= type=` (never the exception message).
- New mappings: ReservationExpired 410; RateLimitExceeded 429 (+`Retry-After` seconds rounded up, min 1, omitted when unknown); ConcurrentInventoryModification 409 `concurrent-modification` + `Retry-After: 1`; InvalidStateTransition / OrderStatusConflict / OrderAlreadyExists 409 with fixed messages; any `ErrorResponse` (404 not-found, 405 + `Allow`, 406, 413, 415, other 4xx, `ResponseStatusException`) with fixed messages; only `Allow`, `Accept`, `Retry-After` of the exception headers are forwarded.
- **409 vs 503 for concurrent modification**: 409. The request collided with a concurrent change and was not applied; it is safe to replay (purchases carry Idempotency-Key) so a `Retry-After` hint is given. The F-020 description also lists it under 409. 503 stays for "service cannot accept work" (queue down). Documented in README.
- Retry: the web layer does not retry (cannot know if replay is safe); adapter-level `Retry.backoff` for transient errors already exists (SQS publisher); clients are guided with `Retry-After`.
- Correlation id: `CorrelationIdWebFilter` (HIGHEST_PRECEDENCE) accepts `[A-Za-z0-9._-]{1,64}` else UUID; sets exchange attribute, response header (set at start and again in `beforeCommit`, so error handlers cannot drop it), and Reactor context key `correlationId` (same key `SqsOrderQueuePublisher.CORRELATION_ID_CONTEXT_KEY` reads).
- Context -> MDC mechanism (verified, not guessed): reactor-core 3.8.7 delegates to `io.micrometer:context-propagation` (not on the classpath before; added). `CorrelationContextPropagation.install()` registers a `ThreadLocalAccessor` (key `correlationId` <-> SLF4J MDC) in `ContextRegistry` and calls `Hooks.enableAutomaticContextPropagation()`; invoked from `CorrelationConfig` constructor (idempotent). Reactor restores MDC around each signal and clears it after, on any thread. Mutation check: removing the Hooks call makes both MDC tests fail.
- Log pattern uses Boot's `logging.pattern.correlation`; renders `[<id>]`, `[]` outside requests.

## Tests
- Every mapping parameterised (status, type, title, detail, instance, correlationId, header, content type) + no-leak assertions (secret message, class names, `at com.`, `trace`, `path`) + log event with stack trace and MDC id; 429/Retry-After (known/unknown), 409 Retry-After; unknown route/405+Allow/406/415/400; id accepted when safe (incl. 64 chars), replaced when blank/whitespace/space/`;`/`<`/non-ASCII/`/`/65 chars, present on 2xx.
- `CorrelationMdcIsolationTest`: 120 concurrent requests, random delays, thread hops; every log line's MDC id equals its request's id; afterwards 200 tasks on boundedElastic/parallel see no id.
- `ErrorHandlingEndToEndIT` (real DynamoDB Local + LocalStack SQS, full Boot context, consumer disabled): unknown route 404, 405+Allow, 415, 400 malformed, 413 oversized body (600 KB), regression 404/409/400 paths with correlationId, unsafe id replaced, POST /orders with client id -> SQS message attribute `correlationId` read from the real queue, raw-socket header injection (`\n` + Set-Cookie) not echoed/logged.

## Verification
- `./init.sh` (no Docker): `==> init.sh OK`, BUILD SUCCESSFUL.
- `INCLUDE_INTEGRATION=true ./init.sh` (colima: DOCKER_HOST=unix://$HOME/.colima/default/docker.sock, TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock): 580 tests, 0 failures, `==> init.sh OK`; line coverage 99.0%.

## Real docker-compose (`up --build -d`, then `down -v`)
```
curl -si localhost:8080/nope/zzz -H 'X-Correlation-Id: unknown-route-77'
HTTP/1.1 404 Not Found / Content-Type: application/problem+json / X-Correlation-Id: unknown-route-77
{"detail":"The requested resource was not found","instance":"urn:ticketflow:request:unknown-route-77","status":404,"title":"Not found","type":"urn:ticketflow:problem:not-found","correlationId":"unknown-route-77"}

curl -si -X DELETE localhost:8080/events
HTTP/1.1 405 Method Not Allowed / Allow: POST,GET / Content-Type: application/problem+json / X-Correlation-Id: ...
{"...","status":405,"title":"Method not allowed","type":"urn:ticketflow:problem:method-not-allowed","correlationId":"..."}

curl -si -X POST localhost:8080/events -H 'Content-Type: text/plain' -d x
HTTP/1.1 415 Unsupported Media Type / Accept: application/json, application/*+json, application/x-ndjson
{"detail":"The request content type is not supported",...,"type":"urn:ticketflow:problem:unsupported-media-type",...}

curl -si localhost:8080/events            (no header)  -> 200, X-Correlation-Id: f9f382fd-b6e7-4dc4-bba0-a1878965f784
curl -si localhost:8080/events/nope -H 'X-Correlation-Id: bad id;x' -> 404, X-Correlation-Id: 7ea40aa2-... (generated, bad value not echoed)
curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' -H 'X-Correlation-Id: my-trace-42' -d '{bad' -> 400 malformed-request, correlationId my-trace-42

docker-compose stop dynamodb ; curl -si localhost:8080/events/abc -H 'X-Correlation-Id: boom-500'
HTTP/1.1 500 Internal Server Error
{"detail":"An unexpected error occurred. Quote the correlationId when reporting it","instance":"urn:ticketflow:request:boom-500","status":500,"title":"Internal server error","type":"urn:ticketflow:problem:internal-error","correlationId":"boom-500"}

docker-compose logs app | grep ...
app-1 | ... INFO  ... [ctor-http-nio-3] [unknown-route-77] c.t.i.web.error.ApiExceptionHandler : Request rejected: status=404 type=urn:ticketflow:problem:not-found
app-1 | ... INFO  ... [ctor-http-nio-1] [wrong-method-88]   c.t.i.web.error.ApiExceptionHandler : Request rejected: status=405 type=urn:ticketflow:problem:method-not-allowed
app-1 | ... INFO  ... [ctor-http-nio-4] [my-trace-42]      c.t.i.web.error.ApiExceptionHandler : Request rejected: status=400 type=urn:ticketflow:problem:malformed-request
app-1 | ... ERROR ... [c-response-1-14] [boom-500]        c.t.i.web.error.ApiExceptionHandler : Unhandled error while processing request (correlationId=boom-500)
app-1 | software.amazon.awssdk.core.exception.SdkClientException: Unable to execute HTTP request: connection timed out ... dynamodb:8000 (SDK Attempt Count: 9)
app-1 | 	at software.amazon.awssdk.core.exception.SdkClientException$BuilderImpl.build(...)
```
(The 500 body shows none of this; it only exists in the server log.)

## Notes
- Not pushed, F-020 not marked done.
- Observation (out of scope): a DynamoDB outage surfaces as a generic 500 rather than 503; mapping transient infra errors to 503 + Retry-After could be a follow-up.
