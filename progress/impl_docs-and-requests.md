# Informe de implementación: F-025 docs-and-requests

Rama `feature/F-025-docs-and-requests` (desde `main` f0540d4). Sin cambios en código de aplicación. `feature_list.json`: F-025 en `in_progress` (no marcada `done`).

## Archivos
- Reescrito: `README.md` (documento único: pitch, índice, inicio rápido, arquitectura, estructura, instalación, tabla de configuración, referencia de API, catálogo de errores, flujo de compra, colección/demo, pruebas y cobertura con cifras reales, observabilidad, seguridad, CI/CD, decisiones, troubleshooting, limitaciones).
- `docs/architecture.md`: 6 diagramas Mermaid (componentes por capas, secuencia de compra con compensación y replay, expiración y carrera con el consumer, cortesías, estados de `TicketStatus`, ER de DynamoDB), cada uno con explicación. Se conservó todo el texto técnico previo; el detalle SQS/expiración que estaba en el README se movió aquí.
- `docs/security.md`: nueva sección «Controles de la aplicación (detalle)» (rate limiting, límites, seguridad de reintentos, cabeceras/secretos, ciclo de vida) movida desde el README; enlace obsoleto corregido.
- `docs/verification.md` (nivel Newman y sección F-025), `docs/observability.md` (referencia al README), `AGENTS.md` (mapa del repo: README y requests/), `CHECKPOINTS.md` (C6, C11), `progress/current.md`.
- Nuevos: `requests/ticketflow.postman_collection.json` (v2.1, 31 peticiones, 63 aserciones), `requests/ticketflow.local.postman_environment.json`, `requests/run-newman.sh`, `requests/demo.sh`.

## Decisiones de diseño
- Hechos obsoletos corregidos: el catálogo de errores no listaba `401 admin-unauthorized`, `403 admin-disabled` ni `client-error`; la tabla de propiedades omitía `ticketflow.reservation.ttl`, `dynamodb.provisioning-max-attempts/poll-interval`; arquitectura sin `infrastructure.observability`; texto «virtual threads» condicional (no se usan); referencias cruzadas «README, Rate limiting» obsoletas. `409 idempotent-order-not-active` documentado (antes decía «la capa web no debe responder 202»).
- Newman corre en la red de compose (`http://app:8080` y `:8081`): los puertos publicados están en 127.0.0.1 del anfitrión y el contenedor no los alcanza (Colima/Docker Desktop). `run-newman.sh` descubre la red del contenedor `app`, espera su healthcheck y pasa `ADMIN_API_KEY` por entorno (nada secreto en ficheros). Imagen `postman/newman:6.1.3-alpine`.
- Rate limiter sin debilitar: ~15 escrituras por ejecución, `--delay-request 400` (`NEWMAN_DELAY_MS`); dos ejecuciones seguidas pasan.
- Variables de ejecución (`eventId`, `orderId`, `idempotencyKey`) solo como variables de colección; el entorno solo trae `baseUrl`, `managementUrl`, `adminKey` (un valor vacío en el entorno taparía a la variable de colección). Claves de idempotencia `pm-` + GUID (39 caracteres).
- El stream SSE no termina, así que Newman lo omite (`skipStream=true`, `pm.execution.skipRequest()`); en Postman funciona. El 404 JSON del stream de un evento inexistente sí se aserta. El polling de la orden se repite con `setNextRequest` (hasta 100 reintentos).
- `demo.sh`: `set -euo pipefail`, `jq` opcional, sin secretos, termina con código != 0 si algo no es lo esperado; sondea hasta 60 s.
- Hallazgo operativo (no es un fallo de la app): tras recrear solo el contenedor `app` (`docker-compose up -d`), la primera compra tardó ~31 s en pasar a SOLD (un long poll del consumer anterior pudo quedar vivo en LocalStack y recibir el mensaje; reaparece a los 30 s de `visibility-timeout`). No siempre ocurre. Documentado en Troubleshooting; el demo y Newman lo toleran.

## Validación de diagramas (Mermaid)
- `minlag/mermaid-cli:latest` (mmdc 12.0.0) existe pero no funciona en este host Colima aarch64: sin Chrome para arm64 (`ENOENT` del chrome-headless-shell) y la variante `--platform linux/amd64` falla al extraer capas. Se usó una imagen local equivalente (fuera del repo, en `$HOME/.cache/ticketflow-f025`): `node:22-bookworm-slim` + `chromium` + `npm i -g @mermaid-js/mermaid-cli@12.0.0`, con `-p` `{"args":["--no-sandbox"]}`.
- Primer intento: el ER (diagrama 6) falló por (a) `#` en un comentario y (b) `SK` como clave (solo valen PK/FK/UK). Corregido. Resultado final: los 6 bloques renderizan a SVG sin errores (d1 a d6 de 204 a 273 KB). Revisión visual de PNG de los diagramas 1 (simplificado tras ver flechas confusas) y de los renderizados.

## Newman (pila real de compose)
Tres ejecuciones desde el clon limpio y una desde el árbol de trabajo, todas con 0 fallos:
```
requests 31 (0 failed), test-scripts 31, prerequest-scripts 11, assertions 63 (0 failed), duración ~14,5 s
```
Sin `ADMIN_API_KEY` (pila sin clave): 28 peticiones, 57 aserciones, 0 fallos (cortesías omitidas, «sin X-Admin-Key» espera 403 `admin-disabled`). Ejecuciones seguidas sin 429. Cuerpo del informe de la primera ejecución (extracto): `0. Preparación / 1. Eventos / 2. Compra asíncrona (polling a SOLD con 0 reintentos, replay 202, 409 clave reutilizada, 409 inventario insuficiente) / 3. Validación y 404 / 4. Cortesías (401 sin clave, 201, replay 201) / 5. Operación (liveness, readiness, prometheus, 404 de /actuator en 8080)`.

## demo.sh (ejecución real)
Terminó con `Demo finished OK`: evento 201, compra 202 (RESERVED), polling a SOLD, disponibilidad 97/3, replay 202 con el mismo orderId, 409 `idempotency-key-reused`, 400 `invalid-idempotency-key`, cortesías 201 (`available` 95, `complimentary` 2), stream SSE con el valor actual, métricas `ticketflow_*`. Probado también sin `jq` y sin `ADMIN_API_KEY` (paso 8 omitido con aviso).

## Clon limpio (D)
Clon local de la rama en `$HOME/.cache/ticketflow-f025/clone/tf-clean` (otro nombre de directorio, otra red de compose), siguiendo el «Inicio rápido» del README:
1. `git clone -b feature/F-025-docs-and-requests <repo local> tf-clean` (el README usa la URL de GitHub; la rama aún no está publicada): OK, commit 94ab389.
2. `export ADMIN_API_KEY=$(openssl rand -hex 32); docker-compose up --build -d --wait`: OK, 16,7 s (capas en caché), los tres servicios `Healthy`.
3. `./requests/demo.sh`: OK.
4. `./requests/run-newman.sh`: OK (31/63, 0 fallos); segunda ejecución inmediata OK.
5. Las tres peticiones manuales del README (evento, compra 202 + Location, disponibilidad 117/3): OK.
6. Con la pila recreada sin clave: cortesía -> `403 admin-disabled` (verificado con curl y por el demo, que omite el paso 8).
7. `docker-compose down -v`: OK.
Correcciones al README derivadas: aclarar que `ADMIN_API_KEY` debe ser la misma al arrancar y al ejecutar scripts; tolerancia de 60 s/100 reintentos al retraso del primer mensaje. También se verificaron con curl todos los ejemplos de la referencia de API (202, 201, 401 con `WWW-Authenticate: ApiKey`, 400 de `quantity` > 10 y de evento inválido).

## Pruebas y cobertura
- `./init.sh` (sin integración): `BUILD SUCCESSFUL in 30s`, `==> init.sh OK`: 768 pruebas, 0 fallos, líneas 99,40 % (2.139/2.152).
- `INCLUDE_INTEGRATION=true ./init.sh` (Colima: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`): `BUILD SUCCESSFUL in 4m 41s`, `==> init.sh OK`: 929 pruebas, 0 fallos, líneas 99,54 % (2.142/2.152), ramas 95,89 %.
- Informe HTML: `build/reports/jacoco/test/html/index.html`.
- La pila de compose quedó detenida (`docker-compose down -v`) al terminar.

## Pendiente / notas para el reviewer
- F-026 queda como texto plano («ver F-026»); no se creó `docs/aws.md`.
- No se tocó código de aplicación (un comentario de `ClientRateLimiter` sigue diciendo «see the README»; es solo un comentario).
