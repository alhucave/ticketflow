# Informe de implementación: F-026 aws-cloud-native-docs

Rama `feature/F-026-aws-docs` (desde `main` en d709539). **Solo documentación**: nada se desplegó en AWS, no se usó ninguna cuenta ni credencial, no hay IaC ni cambios de código, build, imagen o CI. `feature_list.json` **no** se tocó (ni `in_progress` ni `done`; lo gestiona la sesión principal).

## Archivos

- Creado: `docs/aws.md` (10 secciones con índice y anclas; avisos «nada desplegado» al inicio y en §10; 3 diagramas Mermaid; 8 bloques JSON etiquetados «ilustrativo, no desplegado»).
- Modificados: `README.md` (entrada de índice y sección «Despliegue en AWS (diseño)», renumeración del índice; 2 marcadores F-026 sustituidos), `docs/security.md` (12 marcadores), `docs/observability.md` (4), `docs/architecture.md` (1), `SECURITY.md` (un enlace), `progress/current.md`, este informe.
- `grep -rn F-026` fuera de `progress/`, `feature_list.json` y `docs/aws.md` no devuelve nada; `docs/aws.md` tampoco contiene el marcador.

## Decisiones de diseño y hallazgos verificados en el código

- Dos servicios (`api`: consumer y expiración `false`; `worker`: ambos `true`) con la misma imagen; banderas y valores por defecto verificados (`SqsConsumerProperties`, `ExpirationProperties`, `DynamoDbConfig`, `application.yml`).
- Hallazgos que el documento destaca: nombres de tabla **fijos** en `DynamoDbTables` (sin prefijo => una cuenta por entorno); la región se lee de `ticketflow.dynamodb.region`/`ticketflow.sqs.region`, no de `AWS_REGION`; 1 vCPU/512 MB de compose no es un tamaño válido de Fargate; cada compra hace **3 transacciones** sobre el mismo ítem de inventario (6 WRU) y el GSI `status-reservationExpiresAt-index` tiene clave de baja cardinalidad y mantiene `SOLD`; el SSE hace 1 lectura consistente por cliente e intervalo; con ALB `trust-forwarded-for` es válido (última entrada) y con CloudFront delante no; `ADMIN_API_KEY` es de una sola vigencia (rotar = redeploy con ventana de 401); `readiness` necesita `DescribeTable` en IAM aunque no haya aprovisionamiento; el barrido corre en cada tarea `worker`.
- Apagado: `server.shutdown` por defecto en Spring Boot 4.1.1 verificado con `javap` sobre `ServerProperties` del jar de `spring-boot-web-server-4.1.1` (`Shutdown.GRACEFUL`) y confirmado por el log de `init.sh` («Commencing graceful shutdown»).
- Acciones IAM derivadas de `grep` de llamadas SDK en `src/main`: GetItem, PutItem, UpdateItem, Query, Scan, TransactWriteItems (autorizado por Put/Update; no se usan ConditionCheck ni Delete), DescribeTable (sonda); CreateTable/UpdateTable solo con `ticketflow.dynamodb.provisioning-enabled=true` (por defecto `false`; ningún rol de producción los lleva); SQS: SendMessage, ReceiveMessage, DeleteMessage, GetQueueUrl, GetQueueAttributes. Roles separados `api` y `worker`: `findByIdempotencyKey` no lo llama ningún caso de uso; `findAuditTrail` solo `IssueComplimentaryUseCase`; `findExpiredReservations` solo el barrido.
- Techo por evento (derivado, no medido): 6 WRU por compra frente a 1 000 WRU/s => ≈ 166 compras/s; cota por conflicto `1/(3L)` con `L` supuesto (10/20/50 ms => ≈ 33/17/7 compras/s).

## Mermaid: renderizado y control negativo

mermaid-cli local (`mmdc-local:latest`, Colima, directorio de trabajo bajo `$HOME/.cache/ticketflow-aws/mmd`, `p.json = {"args":["--no-sandbox"]}`). Los 3 bloques ```mermaid se extrajeron del propio `docs/aws.md` y se renderizaron tras la última edición:

```
d1 (topología)            exit=0 svg=277676 bytes
d2 (secuencia de compra)  exit=0 svg=202469 bytes
d3 (pipeline de entrega)  exit=0 svg=203577 bytes
```

Revisé visualmente el PNG de `d1`. Control negativo (`neg.mmd`, subgrafo y nodo sin cerrar):

```
neg exit=1
Error: Parse error on line 3:
... a["nodo"] -->     subgraph X["sin cerr
Expecting 'AMP', 'COLON', 'PIPE', ... got 'subgraph'
```

y no se generó `neg.svg`.

## Validación de JSON

Los 8 bloques ```json de `docs/aws.md` (variables de entorno, SCP, roles `api`, `worker`, ejecución, política de endpoint de DynamoDB, confianza OIDC, despliegue) se extrajeron y `json.loads` los aceptó todos (`json ok 1..8`).

## Enlaces y anclas

Script (fuera del repo) con la regla de slugs de GitHub sobre README, SECURITY.md, AGENTS.md y `docs/*.md`: `checked 223 bad 0` (archivos existentes y anclas existentes). Comprobado que el comprobador detecta anclas inexistentes (`nope` no está en el conjunto).

## Fuentes y fecha de consulta (2026-10-03)

- Páginas de documentación de AWS descargadas con `curl` (citadas con URL en el documento): bp-partition-key-design (3 000 RRU/s y 1 000 WRU/s por partición), transaction-apis (2 lecturas/escrituras subyacentes por ítem, conflictos, `TransactionConflict`, 400 KB), API_TransactWriteItems (100 ítems, 4 MB), transaction-apis-iam, on-demand-capacity-mode (4 000/12 000, doble del pico), GSI (coste de escritura de GSI), Point-in-time-recovery (35 días), quotas-messages (1 MiB, retención 4-14 días, 300 TPS por partición FIFO), high-throughput-fifo, sqs-dead-letter-queues (retención basada en el encolado original), sqs-key-management, API_ContainerDefinition (`stopTimeout` 30 s por defecto, 120 s máximo en Fargate), task_definition_parameters (tamaños Fargate), fargate-capacity-providers (Spot, 2 min), target-group-health-checks (30 s/5/2, fail-open), edit-target-group-attributes (300 s), x-forwarded-headers (append por defecto), waf rate-based, gateway endpoints (sin coste), multi-region-strong-consistency-gt y globaltables (MRSC sin transacciones; MREC último escritor gana), DAX.consistency, GitHub OIDC en AWS. Lo que no se pudo confirmar quedó marcado **to verify** en el texto (p. ej. descuento de Fargate Spot, ventana de reglas de tasa de WAF, herencia del `HEALTHCHECK` en ECS, soporte de `tmpfs` en Fargate, billing por AZ de los endpoints).
- Precios: AWS Price List API (`https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/index.json`, publicado 2026-10-03T05:31:20Z). Ficheros descargados a `$HOME/.cache/ticketflow-aws/prices` (NO versionados): AmazonDynamoDB 20260911124422, AmazonECS 20260911124425, AWSQueueService 20260911124607, AmazonCloudWatch 20260922021715, AWSSecretsManager 20260911124610, awskms 20260911124601, awswaf 20260914163921, AmazonVPC 20260917190528, AWSELB 20260911124544, AmazonECR 20260911124425, AmazonPrometheus 20260911124459, AWSXRay 20260911124622, AWSDataTransfer 20260916132208, AmazonEC2 (us-east-1) 20260925174521. SKU y precios unitarios exactos y aritmética completa en `docs/aws.md` §8 (Fargate 0,04048 por vCPU-h y 0,004445 por GB-h; DynamoDB 0,625 por millón de WRU y 0,125 por millón de RRU; SQS 0,40 por millón; etc.). Total estimado ≈ 223 USD/mes para 1 M de órdenes (≈ 190 fijos; DynamoDB+SQS por uso ≈ 19). No calculados y declarados como tales: GuardDuty, Security Hub, Config, Inspector, Shield Advanced, Grafana gestionado, Route 53, AWS Backup, Fargate Spot (no figura en el fichero de precios).

## Salida de `./init.sh`

```
> Task :test
> Task :jacocoTestCoverageVerification
> Task :check
> Task :build
> Task :jacocoTestReport
BUILD SUCCESSFUL in 30s
11 actionable tasks: 11 executed
==> init.sh OK
```

## Salida de `INCLUDE_INTEGRATION=true ./init.sh` (DOCKER_HOST de Colima y TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock)

```
exit=0
BUILD SUCCESSFUL in 4m 42s
11 actionable tasks: 11 executed
==> init.sh OK
```

## Diff

Solo `README.md`, `SECURITY.md`, `docs/` y `progress/` (ver `git diff main --stat`); sin cambios en `src/`, `build.gradle.kts`, `Dockerfile`, workflows ni `feature_list.json`.

## Notas para el revisor

- Las cifras de capacidad (techo por evento, tamaño de tarea, umbrales) y de coste son estimaciones declaradas como tales; el documento lo dice arriba y en §10.
- La sección 6.6 propone una reconciliación programada que **no existe** (hoy solo está en las pruebas): se marca como diseño.
- No se añadió ningún directorio de IaC ni se descargó ningún fichero de precios al repositorio.
