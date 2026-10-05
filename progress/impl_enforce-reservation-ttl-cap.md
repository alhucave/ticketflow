# Informe: F-030 enforce-reservation-ttl-cap

## Archivos
- `src/main/java/com/ticketflow/domain/model/Order.java`: constante `MAX_RESERVATION_TTL = Duration.ofMinutes(10)` (enunciado: «máximo 10 minutos»).
- `src/main/java/com/ticketflow/usecase/RequestPurchaseUseCase.java`: el constructor (donde ya se validaba el TTL) rechaza `> MAX_RESERVATION_TTL`; los mensajes (positivo y tope) nombran `ticketflow.reservation.ttl`, el valor y el máximo.
- Tests: `RequestPurchaseUseCaseTest` (PT10M ok, PT1S ok, 1 ns ok, +1 ns / +1 s / PT2H rechazados con mensaje, cero/negativo rechazados, expiración == now+10 min con TTL en el tope); `UseCaseConfigTest` (ApplicationContextRunner: arranca con defecto, PT10M, PT1S; falla con PT10M1S y PT2H y el mensaje contiene la propiedad, el valor y PT10M; PT0S y -PT1S fallan).
- Docs: `docs/requirements.md` (RF-2), `docs/decisions.md` (DP-006 actualizado, sin DP nuevo), `README.md` (tabla de configuración), `docs/architecture.md` (flujo de compra).

## Decisiones de diseño
- Origen `spec`: el tope es el requisito del enunciado, por lo que no hay DP nuevo; DP-006 (TTL configurable) se actualiza (ya no «sin tope») y se añade F-030 a su campo Feature. RF-2 sigue «Cumplido con interpretación» (por la configurabilidad/job de DP-006); la matriz y los conteos no cambian.
- Constante en `domain.model.Order` (dominio puro); la validación vive en el caso de uso (sin Spring), que ya validaba el TTL; la excepción de Spring hace fallar el arranque.
- Otros orígenes de expiración revisados: la única derivación `now + TTL` es `RequestPurchaseUseCase`; las cortesías usan `expiresAt == createdAt` (`Order.complimentary`, `transitionTo`); `ProcessOrderUseCase` y el job solo comparan `reservationExpiresAt` con `now`, no derivan expiración. `docs/aws.md` y `docs/observability.md` no describen el TTL como libre: sin cambios.
- Mensaje de error: ahora contiene la propiedad, p. ej. `ticketflow.reservation.ttl must not exceed PT10M (the statement reserves tickets for at most 10 minutes), but was PT15M`.

## Evidencia E2E (stack real: imagen empaquetada + DynamoDB Local + LocalStack, `docker-compose`)
Se usó un override de compose fuera del repo (scratchpad) solo para reenviar `TICKETFLOW_RESERVATION_TTL`, `TICKETFLOW_EXPIRATION_INTERVAL`, `TICKETFLOW_EXPIRATION_INITIAL_DELAY=PT2S` y `TICKETFLOW_SQS_CONSUMER_ENABLED=false` (el consumidor vende la orden al instante y oculta la expiración; sin él la reserva queda visible hasta que la libera el job). `ADMIN_API_KEY` aleatoria, no versionada. `docker-compose down -v` al final de cada escenario.

(a) Configuración por defecto (sin TTL):
```
POST /orders -> {"orderId":"d74d0a61-...","status":"RESERVED","reservationExpiresAt":"2026-10-04T23:43:21.616019174Z"}
GET /orders/d74d0a61-... -> ..."reservationExpiresAt":"2026-10-04T23:43:21.616019174Z","createdAt":"2026-10-04T23:33:21.616019174Z"
expiresAt - createdAt = 0:10:00
```
(b1) `TICKETFLOW_RESERVATION_TTL=PT15M`: la app no arranca; `docker-compose logs app` (contenedor con `restart: unless-stopped`, reintenta y vuelve a fallar: 4 reinicios en ~25 s, `localhost:8080` nunca responde, http=000):
```
Application run failed
... Factory method 'requestPurchaseUseCase' threw exception with message: ticketflow.reservation.ttl must not exceed PT10M (the statement reserves tickets for at most 10 minutes), but was PT15M
```
(b2) `TICKETFLOW_RESERVATION_TTL=PT30S`, `TICKETFLOW_EXPIRATION_INTERVAL=PT5S`: readiness UP; POST creada 23:34:31, `reservationExpiresAt` 23:35:01 (+30 s); disponibilidad `available 45, reserved 5` hasta 23:34:56 y a las 23:35:01 el job la libera: la orden pasa a `AVAILABLE` (sin expiración) y la disponibilidad vuelve a `available 50, reserved 0`.

## init.sh
- `./init.sh` plano: `BUILD SUCCESSFUL in 58s` / `==> init.sh OK` (registro: 30 features, 36 decisiones, 69 filas; gate JaCoCo 90% pasa).
- `INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST colima + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE): `BUILD SUCCESSFUL in 5m 16s` / `==> init.sh OK`.

feature_list.json: F-030 sigue `in_progress` (no se marca `done`).
