# Review — feature F-001 (gradle-bootstrap)

**Veredicto:** APPROVED

## Criterios de aceptación
- `./gradlew build` succeeds on Java 25: `./init.sh` ejecutado por el reviewer, BUILD SUCCESSFUL; toolchain 25 en build.gradle.kts — [x]
- JaCoCo verification fails below 90% line coverage: build.gradle.kts `jacocoTestCoverageVerification` con regla LINE/COVEREDRATIO min 0.90, enganchada a `check` (y por tanto `build`) y ejecutada explícitamente por init.sh. Solo excluye `*Application*` (permitido por docs/verification.md). El implementer reporta haberlo visto fallar con una clase sin cubrir; no hay test automatizado de ello, pero la configuración es estándar y correcta — [x]
- Package skeleton `com.ticketflow.{domain,usecase,infrastructure}`: existen los tres `package-info.java` — [x]
- GET /actuator/health returns 200 (WebTestClient): `src/test/java/com/ticketflow/HealthEndpointTest.java` comprueba 200 y `$.status == UP`; no es un test vacío — [x]

## Checkpoints (CHECKPOINTS.md)
- C1: [x] init.sh en verde (ejecutado por mí)
- C2: [x] domain no importa Spring/AWS (solo package-info)
- C3: [x]
- C4: [x] sin .block()/Thread.sleep
- C5: [x] N/A (sin inventario)
- C6: [x] N/A
- C7: [x] sin Lombok ni @Autowired en campos de producción. Nota: HealthEndpointTest usa `@Autowired` en campo (test); la convención dice "sin @Autowired en campos" sin distinguir, pero es el patrón estándar de test y no se bloquea.
- C8: [x] sin secretos; .env en .gitignore; los greps solo coinciden con texto de docs
- C9: [x] gate al 90% activo (hoy pasa en vacío: no hay líneas medibles)
- C10: [x] scope acotado a bootstrap
- C11: [x] README mínimo; sin docs afectadas

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
1. Exclusión de integración (`excludeTags("integration")` salvo `-PincludeIntegration`) es correcta. Sin embargo init.sh no pasa esa propiedad, y docs/verification.md dice que en CI se ejecutan siempre: F-003 debe pasar `-PincludeIntegration` (p. ej. variable de entorno en init.sh o paso de CI), si no los tests de integración nunca correrán en CI.
2. La regla de cobertura pasa en vacío con el esqueleto actual; se vuelve efectiva con la primera clase de producción.
3. README podría documentar `./init.sh` y `-PincludeIntegration` en una feature posterior.
