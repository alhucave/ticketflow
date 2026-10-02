# Estado actual

Feature en curso: F-002 — docker-compose-infra
Plan:
- docker-compose.yml con app, DynamoDB Local y LocalStack (SQS) con healthchecks y tags fijos
- docker/localstack/init-queues.sh crea orders + orders-dlq (redrive policy)
- .env.example (.env ya ignorado), README en español
- Verificar con docker-compose up --build, awslocal, id/whoami, ./init.sh
Estado: en revisión pendiente (ver progress/impl_docker-compose-infra.md)
