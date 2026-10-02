# ticketflow

Plataforma reactiva de procesamiento de eventos de ticketing (Java 25, Spring Boot 4, WebFlux, DynamoDB, SQS).

## Verificación y CI

- `./init.sh` ejecuta build, tests y la barrera de cobertura (90%). Las pruebas de integración (Docker) se activan con `INCLUDE_INTEGRATION=true ./init.sh`.
- `.github/workflows/ci.yml` corre `./init.sh` en cada PR y push a `main` y sube los reportes como artefacto.
- `.github/workflows/release.yml` publica `ghcr.io/alhucave/ticketflow` al empujar un tag `v*`.

## Infraestructura local con Docker Compose

`docker-compose.yml` levanta tres servicios: `app` (Dockerfile multi-stage, usuario no root), `dynamodb` (DynamoDB Local) y `localstack` (SQS). Requiere Docker y Compose (`docker compose` o el binario `docker-compose`).

```bash
cp .env.example .env        # opcional: los valores por defecto ya funcionan
docker-compose up --build   # construye la imagen y arranca todo
docker-compose down -v      # detiene y elimina contenedores y volúmenes
```

| Servicio | Puerto | Notas |
|----------|--------|-------|
| app | 8080 | `GET /actuator/health` |
| dynamodb | 8000 | `amazon/dynamodb-local:3.3.1`, en memoria |
| localstack | 4566 | `localstack/localstack:4.14.0`, solo SQS |

- Al arrancar, `docker/localstack/init-queues.sh` crea la cola `orders` y su DLQ `orders-dlq` (redrive policy con `maxReceiveCount=3`). El healthcheck de LocalStack solo pasa cuando el script terminó, y `app` espera a que `dynamodb` y `localstack` estén healthy.
- Verificar colas: `docker-compose exec localstack awslocal sqs list-queues`.
- Las imágenes tienen tag fijo (sin `:latest`). LocalStack se fija en `4.14.0` porque las versiones `2026.x` exigen `LOCALSTACK_AUTH_TOKEN`.
- Solo se usan credenciales dummy (`test`/`test`). `.env` está en `.gitignore`; nunca commitees secretos.
