# Arquitectura

## Capas (Clean Architecture)

Paquete raíz: `com.ticketflow`.

```
com.ticketflow
├── domain            # Núcleo: sin Spring, sin AWS (Reactor solo en domain.port)
│   ├── model         # records, enums (TicketStatus), value objects
│   ├── exception     # errores de dominio
│   └── port          # interfaces de salida (repositories, publishers) devuelven Mono/Flux
├── usecase           # Casos de uso: orquestan puertos del dominio
└── infrastructure
    ├── web           # Controllers WebFlux, DTOs, mappers, error handler
    ├── persistence   # Adaptadores DynamoDB (AWS SDK v2 async)
    ├── messaging     # Adaptadores SQS (publisher y consumer)
    ├── scheduler     # Job de expiración de reservas
    └── config        # @Configuration, properties
```

**Regla de dependencias:** `infrastructure → usecase → domain`. Nunca al revés. `domain` no importa Spring ni AWS SDK. `usecase` solo conoce puertos (`domain.port`) y puede usar `reactor-core` (`Mono`/`Flux`). Los casos de uso son clases sin anotaciones de Spring; sus beans (y `Clock`, `IdGenerator`) se declaran en `infrastructure.config.UseCaseConfig`.

## Estados de una entrada

`AVAILABLE`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`, `COMPLIMENTARY`.

- Una entrada tiene un solo estado a la vez.
- Transiciones atómicas y auditables (cada transición deja un registro con timestamp, estado origen/destino y actor).
- `RESERVED` y `PENDING_CONFIRMATION` **no son ventas**.
- `SOLD` es final e irreversible. `COMPLIMENTARY` es final pero no contable.

Transiciones válidas:

```
AVAILABLE ──► RESERVED ──► PENDING_CONFIRMATION ──► SOLD
    ▲             │                  │
    └─────────────┴──────────────────┘   (expiración / cancelación)
AVAILABLE ──► COMPLIMENTARY
```

## Modelo de datos (DynamoDB)

Inventario por **contadores** por evento (no una fila por asiento): `available`, `reserved`, `pendingConfirmation`, `sold`, `complimentary`, más `version`.

- `events`: PK `eventId`.
- `inventory`: PK `eventId`; los contadores se modifican **solo** con `UpdateItem` + `ConditionExpression` (p. ej. `available >= :qty`), incrementando `version` en la misma escritura atómica; sin lectura-modificación-escritura. Es el mecanismo anti-sobreventa.
- `orders`: PK `orderId`; atributos `eventId`, `quantity`, `status`, `idempotencyKey`, `reservationExpiresAt`, `createdAt`. GSI por `idempotencyKey` y GSI por `status` + `reservationExpiresAt` para el barrido de expiración.
- `order_audit`: PK `orderId`, SK `timestamp`; registro de transiciones (`from`, `to`, `actor` y `reason` opcional). El valor de la SK es `<ISO-8601 con 9 decimales>#<uuid>`: ordena cronológicamente y dos entradas del mismo instante no colisionan. Los timestamps usados como clave (`reservationExpiresAt` en el GSI de expiración) se guardan con ancho fijo (9 decimales) para que el orden lexicográfico coincida con el cronológico.
- Transición de estado de una orden (`OrderRepository.transition`): un único `TransactWriteItems` con `Update` condicionado a `status = :expected` + `Put` de la auditoría; si pierde la carrera falla con `OrderStatusConflictException` (nunca sobrescribe).

Invariante: `available + reserved + pendingConfirmation + sold + complimentary = capacity`.

## Flujo de compra

1. `POST /orders` (`RequestPurchaseUseCase`) valida, reserva con expiración a 10 min (`ticketflow.reservation.ttl`, por defecto `PT10M`), encola un mensaje en SQS y responde `202` con `orderId`. Reserva de inventario (`available → reserved`, condicionada, `version + 1`), alta de la orden en `RESERVED` y auditoría inicial `AVAILABLE → RESERVED` son **un único `TransactWriteItems`** (`OrderPlacementRepository.placeReservation`). El mensaje se publica después; si falla, `releaseReservation` (otro `TransactWriteItems`: orden `RESERVED → AVAILABLE` condicionada + auditoría con `reason` + inventario `reserved → available`) compensa y el error se propaga (`OrderEnqueueFailedException`). F-014 y F-017 reutilizan `releaseReservation`.
   - **Idempotencia**: el `orderId` se deriva determinísticamente del `Idempotency-Key` (UUID de nombre, SHA-256), así que `attribute_not_exists(orderId)` garantiza una sola orden por clave incluso con peticiones concurrentes. Misma clave y mismo payload devuelve la orden existente (sin reservar ni publicar de nuevo); misma clave con distinto payload falla con `IdempotencyKeyReusedException`. La clave tiene 16-128 caracteres (validado en la capa web).
   - Recuperación de una reserva sin mensaje (F-023): si el proceso muere o el cliente se desconecta entre la transacción y la publicación, la orden queda `RESERVED` sin mensaje. Un reintento con la misma clave y payload sobre una orden aún `RESERVED` **republica su mensaje** (mejor esfuerzo, sin liberar nada si falla; los duplicados son inocuos porque el consumer es idempotente); si nadie reintenta, la reserva se recupera al expirar (F-017). Además, el controlador desacopla «reservar + publicar + compensar» de la suscripción de la petición (`Detached`), así que una desconexión a mitad de camino no la interrumpe.
   - Clave consumida: si la orden de una clave fue liberada (compensada tras fallar la publicación, o expirada) queda `AVAILABLE`; reintentar con la misma clave y mismo payload lanza `IdempotentOrderNotActiveException` (no reserva ni publica y la capa web no debe responder 202): el cliente debe reintentar con una `Idempotency-Key` nueva. Con distinto payload prevalece `IdempotencyKeyReusedException`.
2. El consumer SQS (at-least-once) invoca `ProcessOrderUseCase` (F-014) con el `orderId`. Es una máquina de estados guiada por el estado actual de la orden (lectura consistente) y **cada paso es idempotente y es un único `TransactWriteItems`** (`OrderFulfillmentRepository`): cambio de estado de la orden condicionado al estado esperado + auditoría + movimiento del contador de inventario (condicionado a `origen >= qty`, `version + 1`).
   - Orden inexistente: `OrderMissing` (sin escrituras). `SOLD`/`COMPLIMENTARY`/`AVAILABLE`: `AlreadyProcessed` (sin escrituras).
   - `RESERVED` vigente: `markPendingConfirmation` (`reserved → pendingConfirmation`) y a continuación `confirmSale` (`pendingConfirmation → sold`) → `Sold`. `PENDING_CONFIRMATION` vigente (p. ej. caída tras el primer paso): solo `confirmSale`.
   - `RESERVED`/`PENDING_CONFIRMATION` con `reservationExpiresAt <= now`: `releaseReservation` (razón `reservation expired`) → `ReleasedAsExpired`; la orden queda `AVAILABLE` (no hay estados EXPIRED/FAILED) y nunca se vende.
   - Concurrencia: con varios workers sobre la misma orden gana exactamente uno por paso (condición de estado); el perdedor recibe `OrderStatusConflictException`, lo trata como benigno releyendo la orden (acotado a 5 lecturas) y termina como `AlreadyProcessed` o ayuda a terminar el paso pendiente. Los errores transitorios de infraestructura se propagan para que SQS reentregue.
3. Mensajes que fallan tras N reintentos van a una DLQ.
4. El scheduler (`ReservationExpirationScheduler`, F-017; `ticketflow.expiration.*`, desactivado por defecto) ejecuta cada minuto `ReleaseExpiredReservationsUseCase`: consulta el GSI `status` + `reservationExpiresAt` (paginado, eventualmente consistente; solo da candidatas) las órdenes `RESERVED/PENDING_CONFIRMATION` con `expiresAt <= now` (la frontera cuenta como expirada, igual que `ProcessOrderUseCase`) y libera cada una con `releaseReservation` (razón `reservation expired`), devolviéndolas a `available`. Concurrencia y tope por barrido acotados; un conflicto de estado (otro barrido o el consumer ganó) es benigno, y el fallo de una orden no aborta el barrido. Nunca hay dos barridos solapados en una instancia; entre instancias basta la escritura condicionada (un único ganador por orden).

## Cortesías (F-021)

`POST /events/{id}/complimentary` (`IssueComplimentaryUseCase`) mueve `AVAILABLE -> COMPLIMENTARY`. Un único `TransactWriteItems` (`OrderPlacementRepository.issueComplimentary`, mismo orden de ítems que `placeReservation`): `[0]` inventario `available -> complimentary` condicionado a `available >= qty` y `version + 1`, `[1]` orden en `COMPLIMENTARY` (`attribute_not_exists(orderId)`), `[2]` auditoría `AVAILABLE -> COMPLIMENTARY` con actor y `reason`. `COMPLIMENTARY` es final y no contable: nada la lleva a `sold` ni la mueve a otro estado (la matriz y `releaseReservation` lo rechazan); sin mensaje en cola; `reservationExpiresAt == createdAt` y el estado no está en el índice de expiración, así que ni el barrido ni `ProcessOrderUseCase` la recogen. Idempotencia con `OrderId.complimentaryFromIdempotencyKey` (espacio de nombres distinto al de las compras). Acceso protegido con `X-Admin-Key` (`AdminKeyWebFilter` + `AdminKeyGuard`, comparación en tiempo constante, deshabilitado por defecto sin `ADMIN_API_KEY`); la autenticación real de usuarios sigue fuera de alcance.

## Endurecimiento de la aplicación (F-023, parte 1)

Detalle y propiedades en el README («Seguridad»). Resumen de diseño:

- **Orden de filtros** (`WebFilter`, de fuera a dentro): `CorrelationIdWebFilter` (MIN) -> `SecurityHeadersWebFilter` (+5) -> `RateLimitWebFilter` (+15) -> `AdminKeyWebFilter` (+20). Los errores de los filtros los renderiza `ProblemWebExceptionHandler`, así que un `429` lleva correlation id y cabeceras de seguridad.
- **Rate limiting** (`infrastructure.web.ratelimit`): `TokenBucket` + `ClientRateLimiter` (Caffeine con tamaño máximo y expiración por inactividad, estado por instancia) + `ClientAddressResolver` (socket por defecto; `X-Forwarded-For` solo con `trust-forwarded-for`, última entrada, IP literal validada). Dos limitadores: escrituras por cliente y intentos fallidos de `X-Admin-Key`. Un despliegue real necesita además una capa de borde (F-026).
- **Errores transitorios**: `ApiExceptionHandler` mapea (después de todos los errores de negocio) throttling/5xx de AWS, `SdkClientException`, `TimeoutException`, transacciones en conflicto y `RetryExhaustedException` a `503` + `Retry-After` con texto fijo (`TransientFailures`).
- **Ciclo de vida**: consumer y scheduler capturan un token de generación por arranque en su predicado de repetición.

## Decisiones clave

- **Optimistic locking / conditional writes** en vez de locks distribuidos: sin coordinación, escala horizontal.
- **Idempotency-Key** en `POST /orders` para soportar reintentos del cliente.
- **Java 25**: records, pattern matching en `switch` sobre estados, sealed types para resultados. Virtual threads solo donde se use código bloqueante (no en el camino reactivo).
- **CD**: imagen publicada en `ghcr.io/alhucave/ticketflow` al crear un tag `v*`.

## Manejo de errores y correlation id (F-020)

- **Un traductor, una forma**: `ApiExceptionHandler.translate(Throwable, correlationId)` convierte cualquier excepción en `ProblemDetail` (`application/problem+json`; `problem(...)` es el único constructor). Lo usan el `@RestControllerAdvice` (errores dentro de controladores) y `ProblemWebExceptionHandler` (`WebExceptionHandler` con orden anterior al `DefaultErrorWebExceptionHandler` de Boot: rutas inexistentes, 405/406/415 de enrutado, excepciones de filtros como el futuro rate limiter), de modo que nunca aparecen la Whitelabel ni `path`/`trace`/`requestId`. Todo lo no mapeado es `500` con texto fijo; el error real se registra con traza.
- **Correlation id**: `CorrelationIdWebFilter` (precedencia máxima) valida/genera el id, lo guarda en el exchange, lo escribe en el contexto Reactor (`correlationId`, que lee `SqsOrderQueuePublisher`) y lo devuelve en la cabecera de toda respuesta.
- **Contexto -> MDC**: `io.micrometer:context-propagation` + `ThreadLocalAccessor` para la clave `correlationId` (MDC de SLF4J) registrado en `ContextRegistry` + `Hooks.enableAutomaticContextPropagation()` (`CorrelationConfig`). Reactor restaura el MDC alrededor de cada señal y lo limpia después, en cualquier hilo, por lo que no se filtra entre peticiones.
- **Reintentos**: solo en adaptadores y para errores transitorios; la capa web comunica `Retry-After` (429, 409 de contención) pero no reintenta.
