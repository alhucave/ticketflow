# Informe F-006 — dynamodb-config-tables

Rama: feature/F-006-dynamodb-config-tables (commit local 9740336, sin push). Estado en feature_list.json: in_progress.

## Archivos
Nuevos (src/main/java/com/ticketflow/infrastructure):
- config/DynamoDbProperties.java: record @ConfigurationProperties(`ticketflow.dynamodb`): endpoint, region (default us-east-1), accessKeyId, secretAccessKey, provisioningEnabled (default false), provisioningMaxAttempts, provisioningPollInterval.
- config/DynamoDbConfig.java: beans DynamoDbAsyncClient y DynamoDbEnhancedAsyncClient; provisioner y starter condicionales a `provisioning-enabled=true`.
- persistence/DynamoDbTables.java: esquema (events, inventory PK eventId; orders PK orderId + GSI idempotencyKey-index y status-reservationExpiresAt-index; order_audit PK orderId, SK timestamp). PAY_PER_REQUEST, timestamps ISO-8601 string.
- persistence/DynamoDbTableProvisioner.java: provisioning reactivo e idempotente.
Tests: DynamoDbConfigTest, DynamoDbTablesTest, DynamoDbTableProvisionerTest (mocks, sin Docker); DynamoDbProvisioningIT (@Tag integration, GenericContainer amazon/dynamodb-local:3.3.1).
Modificados: docker-compose.yml (env TICKETFLOW_DYNAMODB_* del servicio app, provisioning activado), README.md (seccion de configuracion), progress/current.md, feature_list.json (in_progress).

## Decisiones
- Credenciales: estaticas solo si accessKeyId y secretAccessKey estan ambos definidos; si no, DefaultCredentialsProvider. Endpoint nulo = AWS real. Nada hardcodeado (los `test`/`test` solo estan en compose/tests, valores ficticios).
- Provisioning desactivado por defecto: los tests de contexto existentes (HealthEndpointTest) no tocan DynamoDB; compose lo activa por env.
- Sin .block(): el arranque usa @EventListener(ApplicationReadyEvent) + subscribe; los fallos se loguean (no tumban la app). Compromiso consciente por la regla de no bloquear; el Mono provision() es testeable directamente.
- Idempotencia: describeTable -> si no existe, createTable tolerando ResourceInUseException; si existe, solo se agregan GSIs faltantes (updateTable, uno a uno, con espera a ACTIVE); espera final a tabla y GSIs ACTIVE con Retry.fixedDelay (solo ante TableNotActiveException).
- domain sin imports AWS (ArchUnit existente sigue verde).
- Tests de integracion con GenericContainer (misma imagen que compose) en vez de LocalStack; funciona en ubuntu CI con Docker.

## Versiones
AWS SDK BOM 2.55.10 (ya fijado, verificado como release actual en Maven Central). Testcontainers 2.0.5 (gestionado por el BOM de Spring Boot 4.1.1; artefactos testcontainers-junit-jupiter y testcontainers-localstack ya presentes). Imagen amazon/dynamodb-local:3.3.1. build.gradle.kts sin cambios.

## Ejecucion
Docker local (Colima): DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}') y TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock, solo a nivel de shell.
`INCLUDE_INTEGRATION=true ./init.sh`: BUILD SUCCESSFUL, "==> init.sh OK" (IT: 4 tests, 0 fallos, 0 omitidos; jacocoTestCoverageVerification OK).
`./init.sh` plano: BUILD SUCCESSFUL, "==> init.sh OK" (IT excluidos; cobertura >=90% OK).

Salida literal final (integracion):
```
> Task :jacocoTestReport

BUILD SUCCESSFUL in 22s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.8.0/userguide/configuration_cache_enabling.html
==> init.sh OK
```
Salida literal final (plano):
```
> Task :jacocoTestReport

BUILD SUCCESSFUL in 16s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.8.0/userguide/configuration_cache_enabling.html
==> init.sh OK
```
