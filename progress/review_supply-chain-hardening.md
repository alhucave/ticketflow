# Review — feature F-023 parte 2 (supply-chain-hardening)

**Veredicto:** APPROVED

Rama `feature/F-023b-supply-chain-hardening` (commits tras efa1722). Todo verificado por ejecución propia, sin fiarse del informe.

## Criterios de aceptación (parte 2 del PROGRESS de F-023)
- Escaneo de dependencias en CI con SHAs fijados: `security.yml` job `dependencies` (Trivy fs sobre `gradle.lockfile`) y job `image`. Re-ejecutado en local con Trivy 0.75.0: fs 0 hallazgos, imagen reconstruida 0 (debian 13.7: 0, app.jar: 0) — [x]
- Escaneo de secretos en CI: job `secrets` (gitleaks v8.30.1 por digest, historial 97 commits + árbol): "no leaks found" en ambos — [x]. Prueba negativa propia (AWS key + generic key en fichero no-test): detectadas (3 hallazgos); `generic-api-key` en `src/test/java` suprimida, como documenta — [x]
- Dependabot gradle/github-actions/docker: `.github/dependabot.yml` presente y coherente (YAML válido; solo se activa en la rama por defecto) — [x]
- Endurecimiento de contenedor/compose (límites, read_only, cap_drop, no-new-privileges, JRE mínima, HEALTHCHECK) — [x], pruebas abajo
- `docs/security.md` con modelo de amenazas y las LIMITACIONES de la parte 1 (IPv6 /64, por instancia, bloqueo admin por dirección, Idempotency-Key sin principal, 401 vs 403, actor fijo) — [x] (limitaciones 1–6, más 7–9)

## Verificación por ejecución
- `./init.sh`: exit 0, `init.sh OK` (BUILD SUCCESSFUL 23 s).
- `INCLUDE_INTEGRATION=true ./init.sh` (Colima + override de socket): exit 0, `init.sh OK` (BUILD SUCCESSFUL 3m5s).
- Acciones: `aquasecurity/trivy-action` v0.36.0 es tag anotado (a9c7b0f…) que desreferencia a `ed142fd0673e97e23eac54620cfb913e5ce36c25` = SHA fijado; `actions/checkout` v7.0.1 -> `3d3c42e5…` coincide (mismo que ya usa ci.yml). Trivy v0.75.0 existe como release. Digests de `eclipse-temurin:25-jdk@sha256:8c0a84ea…` y `distroless/java25-debian13:nonroot@sha256:ca60da13…` y de gitleaks (`docker manifest inspect`/pull) existen.
- actionlint 1.7.12 sobre security.yml y ci.yml: sin errores.
- `.trivyignore`: sin entradas (solo comentarios y formato); no hay nada que justificar. `.gitleaks.toml`: una única allowlist, `targetRules=[generic-api-key]`, solo `src/test/java/**/*.java`; estrecha.
- Grep de secretos en árbol e historial (patrones AKIA/ghp_/clave privada/etc.): solo el literal de test `AKIAEXAMPLEACCESSKEY1` en `SecretMaskingTest.java` (ficticio, 21 caracteres, no pasa gitleaks); `.env.example` con `test`/`test` y `ADMIN_API_KEY` vacío. C8 OK.
- `verify` y rama protegida: `ci.yml` sin cambios (diff vacío); `gh api` branch protection -> required contexts `["verify"]`; los jobs de `security.yml` tienen otros nombres, no alteran el check obligatorio. El lockfile no rompe `verify` en local (`./init.sh` verde; ci.yml ejecuta `./init.sh`). Sigue pendiente (inherente) la confirmación en el primer CI real; ver observaciones.
- docker-compose endurecido levantado: 3 servicios healthy, `/actuator/health` UP. Flujo completo con curl: crear evento (capacidad 20) -> `POST /orders` (Idempotency-Key 22 caracteres, qty 3) -> `202 RESERVED` -> a los 8 s `SOLD`; replay misma clave -> `202` mismo orderId; `GET /orders/{id}` SOLD; disponibilidad `available 17, sold 3`; clave corta -> `400`. 
- `docker inspect`: app user=nonroot ro=true CapDrop=[ALL] no-new-privileges mem 512 MB cpu 1 pids 200 restart unless-stopped puerto 127.0.0.1:8080; dynamodb user=dynamodblocal, mismo conjunto, 768 MB/256 pids, 127.0.0.1:8000; localstack root (documentado), ro, CapDrop ALL, 1,5 GB/1024, 127.0.0.1:4566. `lsof`: solo `127.0.0.1:{4566,8000,8080}` en escucha. PID 1 de la app uid 65532. Log de la app limpio (única línea que casa con el patrón es el INFO de un 400 esperado, nombre de clase `ApiExceptionHandler`; sin ERROR/WARN/stack traces). `docker-compose down -v` ejecutado; 0 contenedores restantes.
- Documentación: `docs/security.md` en español, contrastada con el código/README/compose (SHA-256 del `orderId`, `MessageDigest.isEqual`, límites 32 KB y max-quantity 10 en `application.yml`, tabla de compose coincide con `docker inspect`). Enlaces relativos comprobados (SECURITY.md, workflow, dependabot) sin rotos; README enlaza a `docs/security.md` y `SECURITY.md`; F-026 existe en `feature_list.json` (pending), no hay referencias muertas. Limitaciones documentadas con honestidad (LocalStack root, imágenes de terceros sin escanear, 8 CVE sin parche de libexpat/libuuid aceptados).

## Checkpoints (CHECKPOINTS.md)
- C1: [x] ambos `./init.sh` verdes ejecutados por el reviewer.
- C2: [x] sin cambios en `src/` (diff vacío); arquitectura intacta.
- C3: [x] los criterios de esta parte son de CI/infra/docs, verificados por ejecución (escaneos, compose, curl); no aplican tests unitarios nuevos y el flujo de compra se prueba extremo a extremo.
- C4: [x] sin código de producción nuevo en la app (solo `docker/healthcheck/Healthcheck.java`, sin bloqueos reactivos).
- C5: [x] no aplica (sin cambios de inventario).
- C6: [x] no aplica (sin cambios de transiciones).
- C7: [x] comentarios/nombres en inglés en workflows, Dockerfile, compose y Java; sin Lombok ni `@Autowired`. Docs en español según convención.
- C8: [x] sin secretos; gitleaks limpio en historial y árbol; solo valores ficticios.
- C9: [x] cobertura sin cambios; init.sh incluye el umbral 90 % y pasó.
- C10: [x] scope acotado a supply chain/CI/contenedor/docs; ediciones menores en `docs/architecture.md` (renombrar referencia de sección), `README.md`, `docs/verification.md` y `progress/current.md` son consecuencia directa. `build.gradle.kts`: locking + `jackson-bom` 3.1.7 justificado por 5 HIGH reales corregibles.
- C11: [x] README, docs/security.md, SECURITY.md, verification.md actualizados.
- C12: [x] no aplica a esta parte (sin nuevos casos de uso); integración existente verde con `INCLUDE_INTEGRATION=true`.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. `docs/security.md` y el informe citan "92 commits" del historial escaneado; hoy son 97 (cifra obsoleta, sin impacto).
2. Pendiente de confirmar solo en el primer CI real (no verificable en local): ejecución de `security.yml` en runners (pull de ghcr, guarda `safe.directory` de gitleaks con propietario real, build amd64 de la imagen), y que `ci.yml`/`verify` y la caché de `setup-java` acepten `gradle.lockfile`. Los jobs de `security.yml` no son check obligatorio (solo `verify`); considerar añadirlos a la protección de rama tras el primer run verde.
3. `jackson-bom` 3.1.7 es un override temporal; retirar cuando el BOM de Spring Boot lo incluya (ya anotado en el código y docs).
4. gitleaks aplica `.gitleaks.toml` del repo automáticamente aunque no se pase `-c`; no afecta.
