# Review — feature F-026 (aws-docs)

**Veredicto:** APPROVED

Rama `feature/F-026-aws-docs` (e4d3728 sobre main d709539). Revisión independiente; no se editó `docs/aws.md`.

## Criterios de aceptación (verificados de forma independiente)
- Solo documentación; nada desplegado, dicho al inicio (`docs/aws.md` líneas 3-10), en README (sección «Despliegue en AWS (diseño)») y en §10 con sus implicaciones (cifras = estimaciones, nada medido en AWS): [x]
- `git diff main --name-only`: README.md, SECURITY.md, docs/{architecture,aws,observability,security}.md, progress/current.md, progress/impl_aws-docs.md. Sin cambios en src/, build, Dockerfile, CI, compose, feature_list.json ni directorio IaC: [x]
- Secciones 1-9 presentes (alcance/mapeo, Mermaid, cómputo api/worker, datos y límites de escalabilidad, seguridad con JSON IAM, observabilidad con tabla de alarmas y runbooks, gobierno + Well-Architected, costes, checklist/brechas/fases) más §10 conclusiones: [x]
- Fragmentos ilustrativos etiquetados «ilustrativo, no desplegado»: [x]

## Verificaciones
1. `./init.sh`: exit 0, BUILD SUCCESSFUL, «init.sh OK». `INCLUDE_INTEGRATION=true ./init.sh` (Colima DOCKER_HOST + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE): exit 0, BUILD SUCCESSFUL en 4m39s.
2. Diff acotado (ver arriba).
3. Mermaid: los 3 bloques extraídos de `docs/aws.md` renderizan con `mmdc-local:latest` (d1 exit 0, 277 679 B; d2 exit 0, 202 469 B; d3 exit 0, 203 577 B). Control negativo (subgraph sin cerrar): exit 1, «Parse error on line 3», sin SVG.
4. JSON: los 8 bloques pasan `json.loads`. Acciones IAM contrastadas con las llamadas del SDK en `src/main` (GetItem, PutItem, UpdateItem, Query, Scan, TransactWriteItems con solo Put/Update, DescribeTable, SendMessage, ReceiveMessage, DeleteMessage, GetQueueUrl, GetQueueAttributes; CreateTable/UpdateTable solo en `DynamoDbTableProvisioner`; no hay Delete ni ConditionCheck). Rol `api` sin Query sobre `orders` coherente: `findByIdempotencyKey` sin llamadores en usecase, `findExpiredReservations` solo en `ReleaseExpiredReservationsUseCase`, `findAuditTrail` en `IssueComplimentaryUseCase`. Propiedad `ticketflow.dynamodb.provisioning-enabled` (default false) correcta en `DynamoDbProperties`.
5. Afirmaciones sobre la app contrastadas con código/config: defaults de `SqsConsumerProperties` (batch 10, wait 20s, visibility 30s, concurrency 4, shutdown 25s), `ExpirationProperties` (PT1M, max-per-sweep 500, shutdown PT20S, enabled false), `RateLimitProperties` (20, 1/s, trust-forwarded-for false; `ClientAddressResolver` usa la ÚLTIMA entrada), `ObservabilityProperties` (queue-metrics false/15s, health 2s/5s), regiones por defecto us-east-1, `ORDERS_QUEUE_NAME`, `TICKETFLOW_ENVIRONMENT`, puertos 8080/8081, ENTRYPOINT (`MaxRAMPercentage=70`, `ExitOnOutOfMemoryError`, `-UsePerfData`), HEALTHCHECK, compose (512m/1 cpu/200 pids, tmpfs, cap_drop), `init-queues.sh` (VisibilityTimeout 30, maxReceiveCount), nombres de tabla fijos, reintentos de Placement/Fulfillment (10, 20 ms, máx. 1 s), poll-interval 1s, `ATTR_CORRELATION_ID`, cabecera `X-Correlation-Id`, y existencia de todas las métricas `ticketflow.*` citadas en las alarmas. Sin discrepancias.
6. Documentación AWS re-descargada con curl y comparada: bp-partition-key-design (3 000 RRU/1 000 WRU por partición), transaction-apis (2 lecturas/escrituras subyacentes, 400 KB, SDK no reintenta, `TransactionConflict`), API_TransactWriteItems (4 MB), on-demand (4 000/12 000, doble del pico), PITR (35 días), task_definition_parameters (0,5 vCPU: 1-4 GB; 1 vCPU: 2-8 GB), target-group-health-checks (30 s; 5 sanos/2 no sanos; fail-open), edit-target-group-attributes (300 s), x-forwarded-headers (append por defecto), API_ContainerDefinition (stopTimeout 30 s por defecto, máx. 120 s Fargate), high-throughput-fifo (300 / 3 000), sqs-dead-letter-queues (marca de encolado original), quotas-messages (1 MiB, 14 días), GSI (2 escrituras si cambia la clave indexada), gateway-endpoints (sin coste), MRSC/MREC transacciones. Todo coincide.
   Precios (Price List API us-east-1, ofertas actuales): coinciden exactamente los 32 SKU citados de ECS/Fargate (x86 y ARM), DynamoDB (WRU, RRU, almacenamiento, PITR, WCU/RCU), SQS estándar y FIFO, ALB, WAF, endpoints VPC, CloudWatch (logs, Insights, alarmas, métricas), Secrets Manager, KMS y ECR. Aritmética recalculada: Fargate 72,08 + 0,25; DynamoDB 16,25/1,25/0,41/0,40; SQS 4 814 400 solicitudes = 1,93; ALB 16,43 + 5,84; WAF 9 + 6; endpoints 73,00; etc. Suma de la tabla = 223,29 (≈ 223); costes fijos 188,75 (≈ 190); AMP 15,06; Graviton 14,42 frente a 18,02 por tarea-mes; provisionado 4,7; cotas por conflicto 33/17/7 compras/s; 6 WRU → ≈ 166 compras/s. Correctos.
7. `grep F-026` en README, docs/, SECURITY.md, AGENTS.md: sin resultados. Comprobador de enlaces relativos y anclas (regla de slugs de GitHub, 223 enlaces en README, SECURITY.md y docs/*.md): 0 rotos.
8. Nada implica despliegue; las cifras sin fuente se marcan «supuesto», «sin medir» o «to verify»; lo no calculado (GuardDuty, Config, Shield Advanced, Spot, etc.) se declara explícitamente como no calculado. §6.6 declara la reconciliación programada como solo diseño.

## Checkpoints (CHECKPOINTS.md)
- C1: [x] init.sh y modo integración en verde, ejecutados por el revisor.
- C2: [x] sin cambios de código.
- C3: [x] feature de documentación; el criterio se valida con las comprobaciones 2-8 (no aplica test de código).
- C4: [x] sin cambios de código.
- C5: [x] sin cambios de código.
- C6: [x] sin cambios de código.
- C7: [x] sin código; documentación en español conforme al resto de docs/.
- C8: [x] solo identificadores de ejemplo (111122223333, UUID ficticio); sin secretos.
- C9: [x] sin cambios de código; la verificación de cobertura de init.sh pasó.
- C10: [x] alcance limitado a docs y enlaces.
- C11: [x] documentación coincide con el código (punto 5) y los marcadores F-026 sustituidos por enlaces válidos.
- C12: [x] sin cambios de casos de uso; las suites de integración existentes pasan.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- `docs/aws.md` §8.3 («Lecturas del resultado»): «DynamoDB + SQS por uso suman ≈ 19 USD»; la suma de las filas es 19,84 (≈ 20).
- §8.1 indica «sin capa gratuita» y el modelo ignora el 1 M gratuito mensual de SQS; es coherente con la declaración, solo conservador.
- La cita de GSI («si el GSI no tiene capacidad suficiente se limitan las escrituras de la tabla base») procede de la guía de capacidad aprovisionada; en on-demand el riesgo es el de partición caliente que el documento ya describe como no medido.
