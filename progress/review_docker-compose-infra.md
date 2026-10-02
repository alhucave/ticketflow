# Review — feature F-002 (docker-compose-infra)

**Veredicto:** APPROVED

## Criterios de aceptación (verificados en vivo, sin tests Java: son de infraestructura)
- `docker-compose up --build` levanta app, DynamoDB Local y LocalStack: build OK; los 3 servicios quedaron Up (healthy); /actuator/health -> UP — [x]
- Cola orders y DLQ existen: `awslocal sqs list-queues` lista orders y orders-dlq; RedrivePolicy en orders apunta al ARN de orders-dlq, maxReceiveCount=3 — [x]
- Contenedor non-root: `id` -> uid=999(app); Config.User=app — [x]
- .env.example documentado y .env ignorado: `git check-ignore -v .env` -> .gitignore:8; .env.example existe con comentarios y valores dummy — [x]

## Checkpoints
- C1: [x] ./init.sh ejecutado por mí: BUILD SUCCESSFUL, "init.sh OK"
- C2: [x] Sin código Java nuevo
- C3: [x] Criterios de infraestructura verificados en ejecución real (ver arriba)
- C4: [x] N/A
- C5: [x] N/A
- C6: [x] N/A
- C7: [x] Comentarios en inglés; README en español como el resto
- C8: [x] Solo credenciales test/test; tags fijos (amazon/dynamodb-local:3.3.1, localstack/localstack:4.14.0), sin :latest
- C9: [x] Sin cambios de código; init.sh con jacoco verification en verde
- C10: [x] Diff limitado a compose, script de init, .env.example, README, feature_list/progress
- C11: [x] README documenta uso, puertos, colas y tags

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- LocalStack fijado en 4.14.0 porque 2026.x exige auth token; está documentado en README y compose.
- `down -v` ejecutado al final: contenedores y red eliminados.
- feature_list.json deja F-002 en in_progress; el leader debe marcarlo done tras el merge.
