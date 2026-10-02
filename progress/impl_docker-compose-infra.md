# Informe F-002 — docker-compose-infra

Rama: feature/F-002-docker-compose-infra (desde main 7a71423). Estado en feature_list.json: in_progress (done solo tras APPROVED).

## Archivos
- Nuevos: docker-compose.yml, docker/localstack/init-queues.sh (ejecutable), .env.example
- Modificados: README.md (seccion Compose, en espanol), feature_list.json (F-002 in_progress), progress/current.md
- Dockerfile y .dockerignore (F-003) se reutilizan sin cambios: ya son multi-stage y non-root (`useradd --system app`, uid 999). .gitignore ya ignoraba `.env` (verificado con `git check-ignore .env`).

## Tags elegidos (consultados en Docker Hub el 2026-10-02)
- amazon/dynamodb-local:3.3.1 (ultima version numerada; `latest` apunta a la misma).
- localstack/localstack:4.14.0. La ultima (2026.09.0) y 2026.04.3 fallan al arrancar con "License activation failed ... set LOCALSTACK_AUTH_TOKEN" (exit code 55; probado). 4.14.0 (2026-02-26) arranca sin token ("Ready."). No es bloqueante.

## Decisiones
- Init script en /etc/localstack/init/ready.d: crea orders-dlq, obtiene su ARN y crea orders con RedrivePolicy (maxReceiveCount=3, configurable por env) y VisibilityTimeout 30.
- Healthchecks: dynamodb con curl (sin -f; cualquier respuesta HTTP = arriba); localstack consulta /_localstack/init/ready hasta `completed: true` (healthy => colas creadas); app con bash /dev/tcp contra /actuator/health porque la imagen JRE no tiene curl y CMD-SHELL usa dash (primer intento fallo por eso; corregido con `CMD bash -c`). Evita instalar paquetes extra en la imagen.
- app depende de dynamodb y localstack con service_healthy. Se pasan AWS_ENDPOINT_URL_DYNAMODB/SQS y credenciales dummy test/test; la app aun no usa AWS (features posteriores).
- DynamoDB Local en memoria (-sharedDb -inMemory); no hay volumenes, `down -v` limpia todo.
- Puertos y nombres de cola configurables via .env con defaults, asi funciona sin .env.

## Verificacion real (docker 29.5.2 / docker-compose 5.6.0)
- `docker-compose config -q`: valid.
- `docker-compose up -d --build` (build de imagen OK) y tras ~40s: `app Up (healthy)`, `dynamodb Up (healthy)`, `localstack Up (healthy)`.
- `curl localhost:8080/actuator/health` -> `{"groups":["liveness","readiness"],"status":"UP"}`
- DynamoDB ListTables sobre localhost:8000 -> `{"TableNames":[]}` (responde).
- Log init: `[init] Creating DLQ orders-dlq`, `[init] Creating queue orders (redrive to orders-dlq after 3 receives)`.
- `awslocal sqs list-queues` -> .../000000000000/orders-dlq y .../000000000000/orders
- `get-queue-attributes orders RedrivePolicy` -> `{"deadLetterTargetArn":"arn:aws:sqs:us-east-1:000000000000:orders-dlq","maxReceiveCount":"3"}`
- `docker exec ticketflow-app-1 sh -c 'id; whoami'` -> `uid=999(app) gid=999(app) groups=999(app)` / `app`
- `docker-compose down -v`: contenedores y red eliminados.
- `./init.sh`: BUILD SUCCESSFUL ... `==> init.sh OK`.

## Notas
- No hay tests automatizados nuevos: los criterios son de infraestructura y se probaron manualmente como arriba (no hay codigo Java nuevo; cobertura inalterada).
- Sin push/PR/merge.
