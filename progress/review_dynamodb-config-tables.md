# Review — feature F-006 (dynamodb-config-tables)

**Veredicto:** APPROVED

## Criterios de aceptación
- Endpoint, region y credenciales vienen de configuración: DynamoDbProperties + DynamoDbConfig; cubierto por DynamoDbConfigTest (8 tests) — [x]
- Tablas y GSIs creados de forma idempotente al arrancar (Testcontainers): DynamoDbProvisioningIT (4 tests, DynamoDB Local real; ejecuta provision() dos veces, añade GSIs faltantes a una tabla existente, y arranque vía ApplicationReadyEvent) + DynamoDbTableProvisionerTest (8 con mocks) — [x]
- Provisioning desactivable por propiedad: @ConditionalOnProperty (default false); DynamoDbProvisioningIT.context_provisioningDisabled_createsNoTables y DynamoDbConfigTest — [x]

## Verificaciones adicionales
- `./init.sh` (sin Docker): verde, JaCoCo >=90% OK. `INCLUDE_INTEGRATION=true ./init.sh` (Colima, DOCKER_HOST solo por env): verde; IT 4/4, 0 fallos, 0 omitidos.
- Sin endpoint/credenciales hardcodeados en src/main. Único literal: default `us-east-1` de region (sobrescribible; aceptable). `test/test` solo en compose (ficticio, convención) y tests. Sin rutas de máquina.
- domain sin imports de Spring/AWS (grep limpio; DomainArchitectureTest verde).
- Esquema coincide con docs/architecture.md: events y inventory PK eventId; orders PK orderId + GSI idempotencyKey y GSI status+reservationExpiresAt; order_audit PK orderId / SK timestamp. Atributos de eventos (name/date/venue/capacity), quantity, createdAt no necesitan definición (DynamoDB schemaless; solo claves/GSIs).
- Idempotencia incl. GSIs: describe -> create tolerando ResourceInUseException; si existe, solo se añaden GSIs faltantes, esperando ACTIVE.
- Versiones: AWS SDK BOM 2.55.10 (ya existente), Testcontainers vía BOM Spring Boot, imagen amazon/dynamodb-local:3.3.1 fijada (misma que compose); build.gradle.kts sin cambios.
- CI ubuntu-latest: Docker disponible, GenericContainer sin dependencias de Colima; CI define INCLUDE_INTEGRATION=true. Viable.
- Trabajo commiteado localmente (9740336, 7b359aa); árbol limpio; sin rama remota que lo contenga (no pusheado).

## Checkpoints (CHECKPOINTS.md)
- C1: [x]
- C2: [x]
- C3: [x]
- C4: [x] sin .block()/Thread.sleep en producción; espera con Retry.fixedDelay reactivo
- C5: [x] N/A (sin cambios de inventario)
- C6: [x] N/A
- C7: [x] sin Lombok ni @Autowired; inyección por constructor
- C8: [x]
- C9: [x]
- C10: [x] cambios en compose/README/feature_list son propios de la feature
- C11: [x] README actualizado

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- Los fallos de provisioning se solo loguean (no tumban la app); decisión documentada y coherente con la regla de no bloquear.
- Retry.fixedDelay para espera de ACTIVE (polling) en vez de Retry.backoff: aceptable, no es error transitorio de negocio.
- docs/verification.md menciona LocalStack para integración; el IT usa dynamodb-local (igual que compose), más fiel.
- Awaitility se usa en el IT vía dependencia transitiva de Spring Boot test; considerar declararla explícitamente.
