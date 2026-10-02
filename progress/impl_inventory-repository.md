# Informe F-008 — inventory-repository

Rama: feature/F-008-inventory-repository (commit local, sin push/PR). Estado en feature_list.json: in_progress.

## Archivos
- Nuevo: infrastructure/persistence/DynamoDbInventoryRepository.java
- Modificados: domain/port/InventoryRepository.java (transition -> reserve/confirmSale/release/issueComplimentary), docs/architecture.md (linea del modelo de datos), feature_list.json (in_progress), progress/current.md
- Renombrado: ConcurrentModificationException -> ConcurrentInventoryModificationException (git mv; unico uso: DomainExceptionsTest, actualizado; javadoc del puerto ya no lo referencia)
- Tests nuevos: DynamoDbInventoryRepositoryTest (18, mocks), DynamoDbInventoryRepositoryIT (9, Testcontainers amazon/dynamodb-local:3.3.1)

## Decisiones
- Cada mutacion = UN UpdateItem: `SET #src = #src - :qty, #dst = #dst + :qty, #version = #version + :one`, condicion `attribute_exists(#key) AND #src >= :qty`, ReturnValues ALL_NEW. Sin lecturas previas.
- Mapeo: reserve available->reserved; confirmSale reserved->sold; release reserved->available; issueComplimentary available->complimentary. pendingConfirmation no tiene operacion en F-008 (el alcance lista solo estas cuatro); release/confirmSale parten de `reserved`. Si F-012+ necesita pasar por pendingConfirmation habra que añadir operaciones al puerto.
- ConditionalCheckFailed: con `returnValuesOnConditionCheckFailure=ALL_OLD`, item presente -> InsufficientInventoryException; item ausente -> EventNotFoundException (en vez de reportar falsamente "insuficiente").
- No se usa expectedVersion (la condicion de cantidad ya es atomica y evita reintentos por contencion), por lo que ConcurrentInventoryModificationException queda solo renombrada y sin lanzarse (sigue en el dominio para uso futuro).
- Reintentos: Retry.backoff(3, 50ms) solo para ProvisionedThroughputExceeded, RequestLimitExceeded, LimitExceeded, InternalServerError, throttling (AwsServiceException.isThrottlingException) y HTTP 503; al agotar se propaga el error original. Condicion fallida y errores 4xx nunca se reintentan. No se reintentan errores de red/timeout del cliente (SdkClientException) porque un UpdateItem no idempotente podria haberse aplicado ya (doble reserva); anotado como decision consciente.
- create (PutItem con attribute_not_exists -> EventAlreadyExistsException) y findByEventId (consistentRead).
- Constructor publico con @Autowired en constructor (no en campo) + constructor package-private con politica de retry para tests.
- Sin .block() en main (verificado con grep).

## Tests
- Unit: expresiones/atributos por cada operacion, una sola llamada updateItem, Insufficient no reintentado (times(1)), not found, throttling+500+503 reintentados (times(4)/times(2)), agotamiento -> error original, error 4xx no reintentado, predicado isTransient, create/find, y test de propiedad (50 seeds x 100 operaciones aleatorias contra evaluador en memoria de la semantica condicional: suma = capacity, sin negativos, version = escrituras exitosas).
- IT: ciclo de vida completo con version, fuentes insuficientes, evento inexistente, 200 reserve paralelos qty 1 sobre capacidad 50 (exactamente 50 OK, 150 Insufficient, estado final 0/50/version 50), 200 reserve de cantidad mixta 1-4 sobre capacidad 100 (reservado = suma de exitos <= capacidad, version = exitos, sobrante < 4), 300 operaciones mixtas paralelas (invariante + version). Sin sleeps. Ejecutado 3 veces extra de forma aislada: estable.

## Salidas
Cobertura global >= 90% (gate de JaCoCo verde en ambas ejecuciones).
`./init.sh` (sin Docker):
```
BUILD SUCCESSFUL in 17s
==> init.sh OK
```
`INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST colima via variables de entorno, nada hardcodeado en el repo):
```
BUILD SUCCESSFUL in 28s
==> init.sh OK
```
