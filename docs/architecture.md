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
   - **Idempotencia**: el `orderId` se deriva determinísticamente del `Idempotency-Key` (UUID de nombre, SHA-256), así que `attribute_not_exists(orderId)` garantiza una sola orden por clave incluso con peticiones concurrentes. Misma clave y mismo payload devuelve la orden existente (sin reservar ni publicar de nuevo); misma clave con distinto payload falla con `IdempotencyKeyReusedException`.
   - Limitación conocida: si el proceso muere entre la transacción y la publicación, la reserva queda en `RESERVED` sin mensaje y se recupera al expirar (F-017); un reintento con la misma clave devuelve la orden pero no republica.
   - Clave consumida: si la orden de una clave fue liberada (compensada tras fallar la publicación, o expirada) queda `AVAILABLE`; reintentar con la misma clave y mismo payload lanza `IdempotentOrderNotActiveException` (no reserva ni publica y la capa web no debe responder 202): el cliente debe reintentar con una `Idempotency-Key` nueva. Con distinto payload prevalece `IdempotencyKeyReusedException`.
2. El consumer SQS (at-least-once) invoca `ProcessOrderUseCase` (F-014) con el `orderId`. Es una máquina de estados guiada por el estado actual de la orden (lectura consistente) y **cada paso es idempotente y es un único `TransactWriteItems`** (`OrderFulfillmentRepository`): cambio de estado de la orden condicionado al estado esperado + auditoría + movimiento del contador de inventario (condicionado a `origen >= qty`, `version + 1`).
   - Orden inexistente: `OrderMissing` (sin escrituras). `SOLD`/`COMPLIMENTARY`/`AVAILABLE`: `AlreadyProcessed` (sin escrituras).
   - `RESERVED` vigente: `markPendingConfirmation` (`reserved → pendingConfirmation`) y a continuación `confirmSale` (`pendingConfirmation → sold`) → `Sold`. `PENDING_CONFIRMATION` vigente (p. ej. caída tras el primer paso): solo `confirmSale`.
   - `RESERVED`/`PENDING_CONFIRMATION` con `reservationExpiresAt <= now`: `releaseReservation` (razón `reservation expired`) → `ReleasedAsExpired`; la orden queda `AVAILABLE` (no hay estados EXPIRED/FAILED) y nunca se vende.
   - Concurrencia: con varios workers sobre la misma orden gana exactamente uno por paso (condición de estado); el perdedor recibe `OrderStatusConflictException`, lo trata como benigno releyendo la orden (acotado a 5 lecturas) y termina como `AlreadyProcessed` o ayuda a terminar el paso pendiente. Los errores transitorios de infraestructura se propagan para que SQS reentregue.
3. Mensajes que fallan tras N reintentos van a una DLQ.
4. El scheduler libera cada minuto las reservas vencidas (`RESERVED/PENDING_CONFIRMATION` con `expiresAt < now`) devolviéndolas a `available`.

## Decisiones clave

- **Optimistic locking / conditional writes** en vez de locks distribuidos: sin coordinación, escala horizontal.
- **Idempotency-Key** en `POST /orders` para soportar reintentos del cliente.
- **Java 25**: records, pattern matching en `switch` sobre estados, sealed types para resultados. Virtual threads solo donde se use código bloqueante (no en el camino reactivo).
- **CD**: imagen publicada en `ghcr.io/alhucave/ticketflow` al crear un tag `v*`.
