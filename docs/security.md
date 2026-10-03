# Seguridad

Modelo de amenazas, controles y límites conocidos de ticketflow. Describe lo que **está implementado y verificado en este repositorio**; los controles que dependen de una cuenta AWS (borde, IAM, KMS, red, auditoría) se documentan en F-026 (ver «Controles en la nube»). Para reportar una vulnerabilidad, ver [`SECURITY.md`](../SECURITY.md).

Contexto: no hay autenticación de usuarios (la API de compra es anónima); la única credencial es la clave de administración (`X-Admin-Key`) de las rutas de cortesías. El repositorio es público: nada sensible se versiona.

## Modelo de amenazas

| Activo | Amenaza | Mitigación (implementada) | Riesgo residual |
|--------|---------|---------------------------|-----------------|
| Inventario / órdenes | **Reintentos maliciosos o accidentales** de `POST /orders` (doble compra, doble reserva) | La `Idempotency-Key` es obligatoria; el `orderId` se **deriva con SHA-256** de la clave (UUID de nombre, espacio de nombres propio para compras y cortesías), así que la unicidad de la clave primaria de DynamoDB garantiza «una orden por clave» sin consulta previa. Reintentar con la misma clave y body devuelve la misma orden (`202`, mismo `orderId`); misma clave con otro body es `409`. Todo cambio de inventario usa escrituras condicionadas | La clave **no está ligada a un usuario** (ver limitaciones) |
| Órdenes de otros clientes | **Adivinar o sondear** la clave de otro cliente (colisionar con su orden, leer su estado) | Longitud mínima de **16** caracteres (máx. 128, `[A-Za-z0-9._:-]`, validada en la capa web); como el `orderId` sale de un hash SHA-256 la clave **no se puede recuperar** a partir de un `orderId` expuesto; los ids de ruta se validan (1-64 caracteres) y la respuesta 404 no repite el valor | Un cliente que use claves predecibles de 16+ caracteres sigue siendo adivinable; usar UUID |
| Inventario | **Reserva sin mensaje** (el cliente se desconecta o el proceso muere entre reservar y publicar en SQS) | La cadena reservar -> publicar -> compensar se **desacopla de la suscripción de la petición** (la desconexión no la cancela); un replay con la misma clave de una orden aún `RESERVED` **republica** el mensaje (duplicados seguros: el consumer es idempotente); el job de expiración libera las reservas no confirmadas (10 min) | Si el proceso muere justo entre la transacción y la publicación, la reserva solo se recupera con el replay o con la expiración (el job está activo en docker-compose y debe activarse en cualquier despliegue) |
| Disponibilidad (CPU, memoria, DynamoDB, SQS) | **Abuso de recursos** por un cliente | Rate limit por cliente (token bucket) en las tres rutas de escritura: `429` + `Retry-After`, rechazo **antes** de leer el body y de tocar DynamoDB/SQS; estado del limitador **acotado** (`max-clients` = 10 000, Caffeine, `idle-ttl` 15 min); body máximo 32 KB (`413`); cabeceras máx. 8 KB (Netty); máx. 10 entradas por orden (`400`); concurrencia acotada del consumer (4) y del job de expiración (4, máx. 500 órdenes por barrido); backoff exponencial acotado ante fallos de SQS | Limitador **por instancia**; con IPv6 la identidad es la dirección completa (ver limitaciones); la protección real contra DDoS es de borde (F-026) |
| Cola SQS | **Mensajes envenenados** que bloquean el procesamiento | Los mensajes inválidos (JSON roto, `version` desconocida, sin `orderId`) se registran sin volcar el cuerpo, no se borran y tras `maxReceiveCount` (3) pasan a la **DLQ** (`orders-dlq`); no detienen el consumer ni al resto del lote | Alguien debe vigilar la DLQ (alarma en AWS: F-026) |
| Inventario | **Reservas que nunca se confirman** (retención de entradas) | Expiración a los 10 min: `ReservationExpirationScheduler` devuelve el inventario; combinada con el máximo por orden y el rate limit acota cuánto puede retener un cliente | Un cliente con muchas IPs puede retener inventario durante 10 min; mitigación de borde (F-026) |
| Clave de administración (`ADMIN_API_KEY`) | **Fuerza bruta** o filtración | Solo por variable de entorno (nunca en el repo, `.env` está en `.gitignore`); **sin valor por defecto**: sin clave la ruta está deshabilitada (`403`); comparación en **tiempo constante** (ambos valores se hashean con SHA-256 y se comparan con `MessageDigest.isEqual`); los intentos **fallidos** tienen presupuesto propio (5, 1 cada 20 s) y, agotado, se responde `429` sin comparar la clave; `toString()` de `AdminKeyGuard`, `DynamoDbProperties` y `SqsProperties` enmascara los secretos; la clave no se registra | Ver limitaciones (bloqueo por dirección, 401 vs 403, actor de auditoría fijo) |
| Credenciales AWS | **Credenciales commiteadas o filtradas** | En local solo valores ficticios (`test`/`test`) para LocalStack y DynamoDB Local; las credenciales estáticas solo se usan si **ambas** están definidas; en AWS aplica la **cadena de credenciales por defecto** (rol IAM de la tarea/pod, sin claves de larga duración); escaneo de secretos en CI sobre el árbol y todo el historial | Producción: guardar secretos (p. ej. `ADMIN_API_KEY`) en **Secrets Manager** con **rotación** y inyectarlos como variables de entorno (F-026); hoy no hay despliegue AWS |
| Registros (logs) | **Falsificación de logs / inyección** vía cabeceras | `X-Correlation-Id` solo se acepta si cumple `[A-Za-z0-9._-]{1,64}`; si no (CR/LF, longitud, otros caracteres) se descarta y se genera un UUID: el valor no autorizado nunca se registra ni se reenvía; los 404 no repiten el id de la ruta; el `reason` de las cortesías no admite caracteres de control; ni cuerpos de mensajes ni secretos se registran | Un cliente puede elegir su propio correlation id válido (sin impacto: es solo una etiqueta) |
| Información interna | **Fuga de información** en errores | Errores `application/problem+json` genéricos, **sin stack traces** ni mensajes de excepción; las fallas de infraestructura se mapean a `503` + `Retry-After` con texto fijo; cabeceras de seguridad en **todas** las respuestas (`nosniff`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'`, `Cross-Origin-Resource-Policy`); `/actuator` solo expone `health`; ids de ruta validados | Los rechazos a nivel Netty (URL malformada como `/%zz`, caracteres ilegales en cabeceras) devuelven un `400` **vacío y sin `X-Correlation-Id`** (no pasan por WebFlux); es inocuo pero no es trazable |
| Cadena de suministro | **Dependencias vulnerables, secretos o acciones/imágenes manipuladas** | Ver «Cadena de suministro» | Ventana entre la publicación de un CVE y su corrección; vulnerabilidades sin parche (ver `.trivyignore` y la lista de aceptados) |
| Contenedor / host | **Escape o abuso desde el contenedor** | Ver «Endurecimiento de contenedores» | Los contenedores de terceros (LocalStack) corren como root dentro del contenedor (sin capacidades) |

## Cadena de suministro

Workflow [`.github/workflows/security.yml`](../.github/workflows/security.yml) (permisos mínimos: `contents: read`; sin subida de SARIF, así que no necesita `security-events`). Se ejecuta en cada `pull_request`, en cada push a `main` y **semanalmente** (lunes 05:17 UTC) para detectar CVE nuevos sin cambios en el código. Es independiente del job obligatorio `verify` de `ci.yml`.

| Job | Herramienta | Qué hace | Falla cuando |
|-----|-------------|----------|--------------|
| `dependencies` | Trivy `fs` (v0.75.0) | Lee `gradle.lockfile` y busca CVE | Hay hallazgos **HIGH/CRITICAL con corrección disponible** (`--ignore-unfixed`) no listados en `.trivyignore` |
| `secrets` | gitleaks (imagen `v8.30.1` fijada por digest) | Escanea **todo el historial git** (`fetch-depth: 0`) y el árbol de trabajo | Hay cualquier hallazgo, o si no se escaneó ningún commit (guarda contra el falso negativo por `safe.directory`) |
| `image` | `docker build` (sin push) + Trivy `image` | Construye la imagen de la app y la escanea | Hay hallazgos **HIGH/CRITICAL con corrección** no listados en `.trivyignore` |

### Bloqueo de dependencias (Gradle dependency locking)

`build.gradle.kts` activa `dependencyLocking { lockAllConfigurations() }` y el fichero `gradle.lockfile` (versionado) fija la versión exacta de **todo** el grafo, incluido el transitivo. Beneficios: builds reproducibles, y Trivy lee el lockfile para escanear exactamente lo que se despliega. Si el lockfile no coincide con lo resuelto, **el build falla** (nadie actualiza una dependencia sin que quede en el diff). `./init.sh` y CI compilan con el lock.

Flujo para actualizar dependencias o cambiar una versión:

```bash
# 1. Edite la versión en build.gradle.kts (o acepte la PR de Dependabot, que actualiza el lockfile)
# 2. Regenere el lockfile
./gradlew dependencies --write-locks
# 3. Revise el diff de gradle.lockfile, ejecute ./init.sh y haga commit de ambos ficheros
```

Si el escaneo marca una dependencia transitiva con corrección, súbala con una restricción/BOM en `build.gradle.kts` (como `jackson-bom` 3.1.7, que corrige CVE de Jackson 3.1.5 que trae Spring Boot 4.1.1) y regenere el lockfile; retire la restricción cuando el BOM de Spring Boot ya traiga la versión corregida.

### Escaneo de secretos

Config en `.gitleaks.toml` (extiende las reglas por defecto). **Única excepción**, estrecha y justificada: la regla genérica `generic-api-key` (basada en entropía) se ignora **solo** en `src/test/java/**/*.java`, donde hay literales de prueba (claves `Idempotency-Key` de ejemplo, constantes `X-Admin-Key` ficticias de los tests) que no son credenciales. Todas las reglas específicas de proveedor (claves AWS, claves privadas, tokens...) siguen aplicando también a los tests y `generic-api-key` sigue aplicando al resto del repositorio. Los valores ficticios de `docker-compose.yml`, `.env.example` y la documentación (`test`/`test`, claves vacías) **no** disparan ninguna regla y no necesitan excepción. Verificado en local: todo el historial y el árbol limpios con esa configuración; una clave AWS plantada y luego borrada de un historial de prueba **sí** se detecta.

Si un hallazgo es real: **revoque la credencial** (reescribir el historial no basta, el repositorio es público) y después elimínela.

### `.trivyignore`

Cada entrada debe indicar id, motivo y fecha de revisión (formato en el propio fichero). Hoy está **vacío**: no hay hallazgos corregibles sin corregir. Hallazgos **sin corrección** disponible (no bloquean, se revisan cada semana en la ejecución programada), vistos en el escaneo local de la imagen: 4 CVE HIGH en `libexpat1` y 4 en `libuuid1` de Debian 13 (CVE-2026-66046, -76956, -76957, -93990; CVE-2026-76642, -78408, -78409, -78410). La app no usa expat ni libuuid de forma directa (el parseo XML/UUID es de la JVM) y la imagen no tiene shell ni utilidades que los invoquen; el riesgo se acepta hasta que Debian publique el parche, que llegará con el siguiente digest de la imagen base (Dependabot).

### Dependabot y fijación de versiones

- [`.github/dependabot.yml`](../.github/dependabot.yml): `gradle`, `github-actions` y `docker`, semanal, con **grupos** (una PR por ecosistema) y máximo de PR abiertas (3/3/2). Cada PR pasa `verify` y los escaneos.
- Todas las acciones de terceros están fijadas por **SHA de commit completo** (con el tag en un comentario), igual que en `ci.yml`.
- Las imágenes base del `Dockerfile` están fijadas por **digest** (`@sha256:...`) además de por tag; la imagen de gitleaks del workflow, también. Para refrescar un digest: `docker pull <imagen:tag>` y `docker image inspect <imagen:tag> --format '{{index .RepoDigests 0}}'`.

### Ejecutar los mismos escaneos en local

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock   # solo con Colima
# Dependencias (gradle.lockfile)
docker run --rm -v "$PWD:/src:ro" -v trivycache:/root/.cache aquasec/trivy:0.75.0 fs \
  --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --ignorefile /src/.trivyignore --skip-dirs build,.gradle /src
# Secretos: historial y árbol
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git --redact --no-banner -c /repo/.gitleaks.toml /repo
docker run --rm -v "$PWD:/repo" -w /repo ghcr.io/gitleaks/gitleaks:v8.30.1 dir --redact --no-banner -c .gitleaks.toml .
# Imagen
docker build -t ticketflow:local . && docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivycache:/root/.cache \
  aquasec/trivy:0.75.0 image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 ticketflow:local
```

## Endurecimiento de contenedores

**Imagen de la app** (`Dockerfile`): build multi-etapa; el runtime es `gcr.io/distroless/java25-debian13:nonroot` fijado por digest: solo la JRE, **sin shell, sin gestor de paquetes, sin curl**, usuario no root (`nonroot`, uid 65532). La etapa de build (JDK) no llega a la imagen final. Banderas de la JVM conscientes del contenedor: `-XX:MaxRAMPercentage=70` (el heap se calcula del límite de memoria del cgroup) y `-XX:+ExitOnOutOfMemoryError` (ante un OOM el proceso muere y `restart` lo relanza en vez de quedar degradado), `-XX:-UsePerfData` (sin ficheros en `/tmp`). `HEALTHCHECK` sin paquetes extra: una clase Java mínima (`docker/healthcheck/Healthcheck.java`, compilada en la etapa de build) consulta `/actuator/health` y sale con 0 solo si responde 200.

**`docker-compose.yml`**:

| Control | app | dynamodb | localstack |
|---------|-----|----------|------------|
| Puertos solo en `127.0.0.1` | sí | sí | sí |
| `read_only: true` | sí (`tmpfs: /tmp`) | sí (`tmpfs: /tmp` con `exec`) | sí (`tmpfs`: `/tmp`, `/var/lib/localstack`, `/root/.cache`) |
| `cap_drop: [ALL]` | sí | sí | sí |
| `no-new-privileges` | sí | sí | sí |
| Límites (memoria / CPU / pids) | 512 MB / 1 / 200 | 768 MB / 1 / 256 | 1,5 GB / 2 / 1024 |
| `restart: unless-stopped` | sí | sí | sí |
| Usuario | no root (65532) | no root (`dynamodblocal`) | root dentro del contenedor (la imagen lo exige), sin capacidades |

Notas:

- Los puertos publicados en `127.0.0.1` no son alcanzables desde otras máquinas de la red (DynamoDB Local y LocalStack **no tienen autenticación**: nunca deben exponerse). El resto del tráfico circula por la red interna de compose.
- DynamoDB Local necesita un `/tmp` con `exec` (extrae y carga una librería nativa de SQLite; los `tmpfs` de Docker son `noexec` por defecto). Es un tmpfs en memoria, no el sistema de ficheros del contenedor, que sigue de solo lectura.
- LocalStack no es una imagen que controlemos: corre como root dentro del contenedor, pero con **todas** las capacidades retiradas, `no-new-privileges` y sistema de ficheros de solo lectura (verificado que arranca y atiende SQS). El estado es efímero (tmpfs).
- Credenciales: solo valores ficticios (`test`), sin secretos en ficheros; `ADMIN_API_KEY` se reenvía desde el entorno/`.env` ignorado por git, sin valor por defecto.
- La app en el contenedor sigue siendo HTTP plano: el TLS se termina en el borde (por eso no se envía `Strict-Transport-Security`).

## Limitaciones conocidas

Aceptadas conscientemente y documentadas; varias se resuelven en la capa de borde (F-026).

1. **IPv6**: el limitador identifica al cliente por la **dirección completa**. Un solo cliente IPv6 suele poseer un prefijo `/64` (2^64 direcciones), es decir, identidades prácticamente ilimitadas: puede esquivar el rate limit rotando direcciones. Mitigación futura: agrupar por `/64` o limitar en el borde (ver F-026).
2. **Limitador por instancia**: el estado vive en la memoria de cada réplica; con N réplicas el presupuesto efectivo es N veces el configurado, y quien rote muchas IP puede expulsar entradas de la tabla acotada. Requiere capa de borde (ver F-026).
3. **Bloqueo del admin por dirección**: el presupuesto de intentos fallidos de `X-Admin-Key` es **por dirección de cliente**; si el administrador legítimo comparte dirección con un atacante (NAT, proxy sin `trust-forwarded-for`) el atacante puede agotar el presupuesto y dejar al admin sin acceso unos ~20 s por cada token consumido (no revela ni valida la clave).
4. **`Idempotency-Key` no ligada a un principal**: no existe autenticación de usuarios, así que la clave no se acota por cliente: dos clientes que usen la misma clave colisionan en la misma orden. Mitigado por el mínimo de 16 caracteres (una clave aleatoria no se adivina) y por derivar el `orderId` con SHA-256 (la clave no se recupera de un `orderId`). Cuando exista autenticación (F-026), la clave debería incluir el principal en la derivación.
5. **401 frente a 403 revela si hay clave configurada**: sin `ADMIN_API_KEY` las rutas admin responden `403 admin-disabled`; con clave configurada y una incorrecta, `401`. Quien sondee puede saber si el servidor tiene una clave. Aceptable para local/desarrollo; no da ninguna pista sobre el valor.
6. **Actor de auditoría fijo**: las cortesías se auditan con el actor fijo `complimentary-issuance`; con autenticación real debe llevar la identidad del llamante (F-026).
7. **Rechazos Netty sin correlation id**: `400` vacío para URL/cabeceras malformadas a nivel de servidor (ver tabla).
8. **`X-Forwarded-For`**: solo con `trust-forwarded-for=true` y exactamente un proxy de confianza (ver README, «Rate limiting por cliente»); mal configurado permite elegir la identidad.
9. **Imágenes de terceros** (DynamoDB Local, LocalStack) solo para desarrollo, sin escaneo en CI ni actualización automática; LocalStack se queda en 4.14.0 (las versiones 2026.x exigen token).

## Controles en la nube

WAF / API Gateway (throttling por clave e IP), IAM de mínimo privilegio, KMS, VPC endpoints, CloudTrail/GuardDuty, alarmas sobre la DLQ y Secrets Manager con rotación: ver F-026.
