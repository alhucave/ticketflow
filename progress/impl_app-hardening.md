# Informe de implementación: app-hardening (F-023, parte 1 de 2)

Rama `feature/F-023a-app-hardening` (desde `main` en `cb39d05`). **Solo la parte 1** (hardening a nivel de aplicación). F-023 sigue `in_progress`: la parte 2 (CI scans, Dependabot, compose/contenedor, `docs/security.md`) no se ha tocado. Mensajes de commit/PR: «Part of #23», nunca «Closes».

## Archivos

### Código de producción (nuevos)
- `infrastructure/web/ratelimit/TokenBucket.java`, `ClientRateLimiter.java`, `ClientAddressResolver.java`, `RateLimitWebFilter.java`
- `infrastructure/config/RateLimitProperties.java`, `RateLimitConfig.java`, `SecretMasking.java`
- `infrastructure/web/SecurityHeadersWebFilter.java`, `Detached.java`, `PathIds.java`, `InvalidPathIdException.java`, `InvalidRequestFieldException.java`
- `infrastructure/web/error/TransientFailures.java`

### Código de producción (modificados)
- `build.gradle.kts`: `com.github.benmanes.caffeine:caffeine` (versión 3.2.4 gestionada por el BOM de Spring Boot 4.1.1; verificado en `spring-boot-dependencies-4.1.1.pom`). Única dependencia nueva.
- `AdminKeyWebFilter` (presupuesto de intentos fallidos), `AdminKeyGuard` (`toString` sin secreto)
- `OrderController` (cantidad máxima configurable, `Detached`, ids validados), `ComplimentaryController`, `EventController`, `AvailabilityController` (ids validados), `PurchaseRequest` (sin `@Max`), `OrderMapper` (clave mínima 16)
- `ApiExceptionHandler` (404 genérico por id inválido, violación de campo, 503 transitorio), `DynamoDbOrderRepository.isTransient` pasa a `public` (fuente única de qué es transitorio)
- `RequestPurchaseUseCase` (republicar en replay de `RESERVED`)
- `SqsOrderConsumer`, `ReservationExpirationScheduler` (token de generación)
- `DynamoDbProperties`, `SqsProperties` (`toString` enmascarado, Javadoc de credenciales)
- `application.yml`: `spring.http.codecs.max-in-memory-size: 32KB`, `ticketflow.orders.max-quantity: 10`
- `README.md`, `docs/architecture.md`, `progress/current.md`, `feature_list.json` (F-023 -> `in_progress`)

### Tests (nuevos)
`TokenBucketTest`, `ClientRateLimiterTest`, `ClientAddressResolverTest`, `RateLimitWebFilterTest`, `RateLimitWebTest`, `RateLimitForwardedWebTest`, `AdminKeyWebFilterTest`, `DetachedTest`, `RateLimitPropertiesTest`, `SecretMaskingTest`, y los ITs `RateLimitEndToEndIT`, `HardeningEndToEndIT` (+ helper `E2eContainers`).

### Tests (modificados)
`OrderControllerTest`, `EventControllerTest`, `AvailabilityControllerTest`, `ComplimentaryControllerTest`, `ComplimentaryDisabledWebTest`, `ErrorHandlingWebTest` (+ `ErrorProbeController`), `CorrelationMdcIsolationTest`, `SqsOrderConsumerTest`, `ReservationExpirationSchedulerTest`, `RequestPurchaseUseCaseTest`, `RequestPurchaseUseCaseIT`, y los ITs `OrdersApiEndToEndIT`, `ComplimentaryApiEndToEndIT`, `EventsApiEndToEndIT`, `ErrorHandlingEndToEndIT` (límites generosos).

## Decisiones de diseño

1. **Rate limiting**: token bucket por cliente (`TokenBucket`, tiempo inyectado) en un `ClientRateLimiter` con Caffeine (`maximumSize` + `expireAfterAccess`, ejecutor síncrono, `Ticker` inyectable). El TTL de inactividad nunca baja del tiempo que el bucket tarda en llenarse (expulsar a un cliente inactivo no pierde nada). Un bucket compartido por cliente para `POST /orders`, `POST /events` y `POST /events/{id}/complimentary`; las lecturas no se limitan. El `Retry-After` sale del propio bucket (`(1 - tokens) / refill`) y lo redondea hacia arriba el `ApiExceptionHandler` existente (F-020). Filtro en `HIGHEST_PRECEDENCE + 15`: después de correlation id (MIN) y cabeceras (+5), antes de admin key (+20) y del enrutado: un rechazo no lee el body ni toca DynamoDB/SQS.
   - **Fuerza bruta de `X-Admin-Key`**: segundo limitador (`admin-failure-*`, por defecto 5 y un intento cada 20 s) que `AdminKeyWebFilter` consulta *antes* de comparar la clave: agotado, 429 sin comparar (ni la clave correcta pasa hasta que se rellene un token). Solo se cobra un intento fallido (clave ausente o incorrecta); el 403 «sin clave configurada» no es una adivinanza y no cuenta. `blockedFor` no crea entradas para clientes desconocidos.
   - **Identidad del cliente**: dirección del socket. `X-Forwarded-For` solo con `ticketflow.rate-limit.trust-forwarded-for=true` (por defecto `false`), y entonces la **última** entrada (la que añade el proxy de confianza; las anteriores son spoofables), que debe ser IP literal estricta (IPv4 con octetos 0-255, o IPv6 con ':' y solo `[0-9a-fA-F:.]`; nunca se resuelven nombres, así que ninguna entrada puede provocar DNS); si no es válida se cae al socket. IPv6 se canonicaliza. Documentado el riesgo en ambos sentidos (README y Javadoc).
   - **Límites declarados**: estado por instancia (N réplicas = N veces el presupuesto) y expulsión por tamaño que un atacante con muchas IP podría usar para reiniciar presupuestos ajenos; el README dice que un despliegue real necesita capa de borde (API Gateway/WAF) y lo marca para el doc de AWS (F-026).
   - Los filtros se registran con `@Bean` en `RateLimitConfig` (no `@Component`) para que los slices `@WebFluxTest` no tengan que resolver sus dependencias; `AdminKeyWebFilter` sigue siendo `@Component`, así que los slices que lo importan ahora importan también `RateLimitConfig`.
2. **Límites de entrada**: `spring.http.codecs.max-in-memory-size=32KB` (la propiedad en Boot 4 es `spring.http.codecs.*`, **no** `spring.codec.*`; la primera versión la tenía mal y el IT de 413 lo detectó). El 413 sale por el mapeo `ErrorResponse` existente (misma forma problem+json). No añadí comprobación de `Content-Length` (el límite acota el buffer igualmente, también con chunked). Cantidad máxima: `ticketflow.orders.max-quantity` (10) vía `@Value` en `OrderController`, comprobada en el controlador y devuelta como `validation-error` con una violación (`InvalidRequestFieldException`, mismo constructor `validation(...)` que bean validation); `@Max` fijo eliminado de `PurchaseRequest`. `Idempotency-Key`: 16-128, charset intacto, **validado solo en la capa web** (`OrderMapper.KEY_MIN_LENGTH`), no en el dominio: `IdempotencyKey` se reconstruye al leer órdenes de DynamoDB y una orden antigua con clave corta rompería `GET /orders/{id}`. Ids de ruta: `PathIds` (`[A-Za-z0-9._-]{1,64}`) en los 5 puntos de entrada; inválido -> `InvalidPathIdException` (sin mensaje ni input) -> 404 `not-found` con texto fijo. Un id válido pero inexistente sigue siendo `event-not-found` (repite un id ya validado).
3. **Seguridad de reintentos**: (a) en `replay`, si la orden existente está `RESERVED` se republica su mensaje (`republish`: `Mono.defer`, mejor esfuerzo, `onErrorResume` -> log WARN con solo la clase de la excepción, nunca libera ni falla). Efecto colateral aceptado y documentado: N réplicas en paralelo con la orden aún `RESERVED` publican hasta N mensajes duplicados (el consumer es idempotente; el limitador acota el abuso); por eso actualicé dos asserts de `RequestPurchaseUseCaseIT` (antes `hasSize(1)`). (b) `Detached.detach` = `Mono.deferContextual(ctx -> work.contextWrite(ctx).cache())`: `cache()` suscribe la fuente una sola vez y un suscriptor que cancela solo deja de escuchar; el contexto del primer suscriptor se fija explícitamente porque, tras cancelar el único suscriptor, un `retry` interno volvería a suscribirse con contexto vacío (test dedicado). Se aplica a `requestPurchase.execute` y a `issueComplimentary.execute`. Riesgo asumido: una cadena colgada ya no se cancela con el cliente (los clientes AWS SDK tienen timeouts).
4. **Ciclo de vida**: cada `start()` incrementa un `volatile long generation`; el predicado de repetición es `running && generation == miGeneración`. Hallazgo: sin el token, tras `stop()+start()` durante un drenaje el bucle viejo (con su `stopSignal` ya emitido, así que `takeUntilOther` completa al instante) **gira en un bucle apretado** repitiendo sin parar mientras `running` es `true` otra vez; lo comprobé mutando el código (los tests nuevos se cuelgan por ese spin sin el fix). En el scheduler el viejo habría duplicado los barridos.
5. **503 transitorio**: `TransientFailures.isTransient` (`TimeoutException`, `SdkClientException`, `AwsServiceException` con throttling/429/5xx o `DynamoDbOrderRepository.isTransient` —incluye `TransactionCanceledException` por conflicto—, `Exceptions.isRetryExhausted`). Es un `case ... when` situado *después* de todos los mapeos de negocio y de `ErrorResponse` (un 503 de framework sigue siendo `internal-error`), y antes del `default`. Mismo `problem(...)`, tipo `service-unavailable`, `Retry-After: 5`, texto fijo; se registra solo la clase (WARN, sin traza ni mensaje). `ConditionalCheckFailedException` y un 400 de AWS siguen siendo 500.
6. **Cabeceras y secretos**: `SecurityHeadersWebFilter` (`@Component`, +5; se fijan al inicio y otra vez en `beforeCommit` como el filtro de correlation id): `nosniff`, `no-store`, `no-referrer`, `X-Frame-Options: DENY`, CSP `default-src 'none'; frame-ancestors 'none'`, `Cross-Origin-Resource-Policy: same-origin`. Sin HSTS (TLS en el borde). `toString` enmascarado en `DynamoDbProperties`, `SqsProperties` (claves `****`, endpoints a esquema/host/puerto, `ordersQueueUrl` oculta) y `AdminKeyGuard`/`AdminKeyWebFilter` (el guard guarda solo un digest; ahora tampoco lo imprime). Credenciales estáticas: ya solo se usaban si ambas estaban definidas; reforzado en el Javadoc.
7. **Netty-level 400** (URL mal formada, cabeceras ilegales): documentado en el README: sin `X-Correlation-Id` ni cabeceras de seguridad porque ningún filtro corre.

## Tests por criterio

| Ítem | Unit/slice | C12 (DynamoDB + SQS reales) |
|------|-----------|------------------------------|
| Rate limit | `TokenBucketTest`, `ClientRateLimiterTest` (memoria acotada, expulsión, TTL mínimo, deshabilitado), `ClientAddressResolverTest`, `RateLimitWebFilterTest`, `RateLimitWebTest` (429 + `Retry-After` + problem+json + cabeceras), `RateLimitForwardedWebTest`, `RateLimitPropertiesTest` | `RateLimitEndToEndIT`: 429 tras agotar en POST /orders (otro cliente y las lecturas intactas, nada reservado por el rechazado), 429 en cortesías, fuerza bruta de admin (incluida la clave correcta bloqueada, otro cliente no), clave ausente cuenta |
| Fuerza bruta admin | `AdminKeyWebFilterTest` (bloqueo sin comparar, desbloqueo al rellenar, aislamiento por cliente, éxitos no cobran, 403 no cobra, rutas no admin) | idem |
| Body/cantidad/clave/ids | `OrderControllerTest` (clave 16, cantidad configurable, ids hostiles), `EventControllerTest`, `AvailabilityControllerTest`, `ComplimentaryControllerTest` | `HardeningEndToEndIT`: 413 en /orders y /events, clave corta 400 y 16 OK, `max-quantity=4`, ids hostiles (5 rutas, sin eco) |
| Replay RESERVED | `RequestPurchaseUseCaseTest` (republica una vez, fallo contenido y sin internals en logs, throw síncrono, estados posteriores no republican) | `HardeningEndToEndIT`: reserva sin mensaje + replay -> el mensaje aparece en la cola real, una sola reserva; replay de `SOLD` no publica |
| Cancelación | `DetachedTest` (publicación exacta una vez, compensación tras cancelar, fallo de compensación, control negativo sin `Detached`, contexto fijado tras retry, cortesías) | `HardeningEndToEndIT`: WebClient real cancelado con la publicación en una puerta; el servidor ve `CANCEL`; después el mensaje aparece **exactamente una vez** con el `correlationId`, orden `RESERVED`; variante con fallo -> compensación, `available == capacity`, sin mensaje |
| 503 | `ErrorHandlingWebTest` (7 tipos transitorios, no-503 para conflictos de negocio y 400/ConditionalCheck, log sin internals) | `HardeningEndToEndIT`: cliente DynamoDB real envuelto que lanza `ProvisionedThroughputExceeded`: GET y POST dan 503 + `Retry-After: 5`, sin fugas, nada queda reservado; luego 409 de negocio |
| Cabeceras | `ErrorHandlingWebTest` (éxitos, 404, 405, 415, 400, 429, 503, 500) | `HardeningEndToEndIT`: 11 respuestas distintas incluyendo `/actuator/health` |
| Lifecycle | `SqsOrderConsumerTest` y `ReservationExpirationSchedulerTest`: restart durante drenaje/barrido, varios reinicios | `HardeningEndToEndIT`: consumer real reiniciado 3 veces con 6 órdenes -> todas `SOLD` una vez; scheduler real reiniciado -> libera la reserva expirada |
| Secretos | `SecretMaskingTest` (por objeto), `AdminKeyWebFilterTest.toString_...` | logs de compose sin credenciales (abajo) |

Verificación por mutación (restaurado después): quitar la comprobación de generación cuelga los tests de restart (spin del bucle viejo); quitar `Detached` hace fallar los dos ITs de cancelación (`reserved=3` sin mensaje).

Cobertura de líneas (JaCoCo, gate 90%): 99,42 %.

## `./init.sh` (sin integración)

```
==> Validating feature_list.json
==> Building and verifying (tests + 90% coverage gate)
BUILD SUCCESSFUL in 23s
==> init.sh OK
```

## `INCLUDE_INTEGRATION=true ./init.sh` (Colima; `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`)

```
==> Validating feature_list.json
==> Integration tests enabled (INCLUDE_INTEGRATION=true)
==> Building and verifying (tests + 90% coverage gate)
BUILD SUCCESSFUL in 3m 6s
==> init.sh OK
```
813 tests, 0 fallos, 0 omitidos.

Nota: una primera ejecución con integración falló en `OrdersApiEndToEndIT`, `ComplimentaryApiEndToEndIT` y `RequestPurchaseUseCaseIT`: (a) el limitador por defecto (20) cortaba las ráfagas de 40 peticiones desde una misma IP -> los ITs de otras features ahora fijan presupuestos generosos (`E2eContainers.generousRateLimits`); (b) dos asserts `published.hasSize(1)` codificaban el comportamiento antiguo de replay -> actualizados a propósito.

## Verificación real con `docker-compose up --build -d` (imagen construida de este árbol, sin `.env`)

Salud y cabeceras (cualquier respuesta, incluido `/actuator/health`):
```
HTTP/1.1 200 OK
X-Correlation-Id: 197d03e6-...
Cross-Origin-Resource-Policy: same-origin
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'
Referrer-Policy: no-referrer
Cache-Control: no-store
```

`POST /orders` en bucle con claves distintas (el evento se creó antes, un token gastado):
```
request 1 -> 202 ... request 20 -> 202
request 21 -> 429
HTTP/1.1 429 Too Many Requests
Retry-After: 1
Content-Type: application/problem+json
X-Correlation-Id: 67164ff0-43c0-4bcf-9619-3ed0a465a0f1
(+ las 6 cabeceras de seguridad)
{"detail":"Rate limit exceeded; retry later","instance":"urn:ticketflow:request:67164ff0-...","status":429,"title":"Too many requests","type":"urn:ticketflow:problem:rate-limit-exceeded","correlationId":"67164ff0-..."}
GET /events/{id}/availability del mismo cliente -> 200
```

Body de 100 KB a `POST /orders` (tras esperar a que se rellenara el bucket):
```
HTTP/1.1 413 Request Entity Too Large
Content-Type: application/problem+json
X-Content-Type-Options: nosniff  (y resto de cabeceras)
{"detail":"The request body exceeds the maximum allowed size",...,"status":413,"title":"Payload too large","type":"urn:ticketflow:problem:payload-too-large",...}
```

`Idempotency-Key: short-key`:
```
HTTP/1.1 400 Bad Request
{"detail":"Idempotency-Key must be at least 16 characters",...,"type":"urn:ticketflow:problem:invalid-idempotency-key",...}
```

Ids de ruta inválidos (`/events/..%2F..%2Fetc%2Fpasswd`, `/orders/%3Cscript%3E...`) -> 404 genérico, sin eco:
```
{"detail":"The requested resource was not found",...,"status":404,"title":"Not found","type":"urn:ticketflow:problem:not-found",...}
```
(`/events/nope`, id válido inexistente: `event-not-found` con `Event not found: nope`, como antes.)

`quantity: 11` -> `400 validation-error` con `{"field":"quantity","message":"must be less than or equal to 10"}`. Ruta inexistente -> 404 problem+json con todas las cabeceras. Ruta admin sin `ADMIN_API_KEY` -> 403 `admin-disabled` (la fuerza bruta con clave configurada está cubierta por el IT).

`docker-compose logs app` (43 líneas): `grep -i -E "secret|accesskey|access-key|access_key|password|credential|AKIA|ADMIN_API|token"` -> **0 coincidencias**; solo aparecen líneas de arranque y `Request rejected: status=429/413 type=...` sin mensajes de excepción.

Después: `docker-compose down -v` (contenedores, red y volúmenes eliminados; `docker ps` vacío).

## Pendiente / para el reviewer
- C12: los casos de uso que combinan puertos ya tienen ITs con adaptadores reales; los nuevos ITs ejercen replay con cola real, cancelación con DynamoDB+SQS reales, throttling inyectado en el cliente DynamoDB real, y reinicios del consumer/scheduler reales.
- Sin `.block()` en código de producción (solo en los ITs, para sembrar estado).
- Parte 2 sin tocar: `docs/security.md` (debe documentar: actor fijo `complimentary-issuance`, 401 vs 403 revela que no hay clave, alcance por principal de la `Idempotency-Key` de F-012, comportamiento de límite por instancia).
- El alcance «`Idempotency-Key` por principal» (nota de F-012) no forma parte de esta entrega (no hay autenticación de usuarios); queda para la parte 2/`docs/security.md`.
