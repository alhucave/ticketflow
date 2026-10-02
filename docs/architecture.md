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

**Regla de dependencias:** `infrastructure → usecase → domain`. Nunca al revés. `domain` no importa Spring ni AWS SDK. `usecase` solo conoce puertos (`domain.port`) y puede usar `reactor-core` (`Mono`/`Flux`).

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
- `order_audit`: PK `orderId`, SK `timestamp`; registro de transiciones (`from`, `to`, `actor`). El valor de la SK es `<ISO-8601 con 9 decimales>#<uuid>`: ordena cronológicamente y dos entradas del mismo instante no colisionan. Los timestamps usados como clave (`reservationExpiresAt` en el GSI de expiración) se guardan con ancho fijo (9 decimales) para que el orden lexicográfico coincida con el cronológico.
- Transición de estado de una orden (`OrderRepository.transition`): un único `TransactWriteItems` con `Update` condicionado a `status = :expected` + `Put` de la auditoría; si pierde la carrera falla con `OrderStatusConflictException` (nunca sobrescribe).

Invariante: `available + reserved + pendingConfirmation + sold + complimentary = capacity`.

## Flujo de compra

1. `POST /orders` valida, reserva atómicamente (`available → reserved`) con expiración a 10 min, persiste la orden en `RESERVED`, encola un mensaje en SQS y responde `202` con `orderId`.
2. El consumer SQS (at-least-once) toma el mensaje, mueve la orden a `PENDING_CONFIRMATION`, revalida la reserva y confirma (`→ SOLD`). Es **idempotente**: reprocesar un mensaje no cambia el resultado.
3. Mensajes que fallan tras N reintentos van a una DLQ.
4. El scheduler libera cada minuto las reservas vencidas (`RESERVED/PENDING_CONFIRMATION` con `expiresAt < now`) devolviéndolas a `available`.

## Decisiones clave

- **Optimistic locking / conditional writes** en vez de locks distribuidos: sin coordinación, escala horizontal.
- **Idempotency-Key** en `POST /orders` para soportar reintentos del cliente.
- **Java 25**: records, pattern matching en `switch` sobre estados, sealed types para resultados. Virtual threads solo donde se use código bloqueante (no en el camino reactivo).
- **CD**: imagen publicada en `ghcr.io/alhucave/ticketflow` al crear un tag `v*`.
