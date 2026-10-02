# Verificación

Una feature está verificada cuando `./init.sh` termina en verde y cada criterio de `acceptance` tiene al menos un test que lo demuestra.

## `./init.sh`

1. Valida que `feature_list.json` sea JSON válido y que haya como máximo una feature `in_progress`.
2. Si existe `./gradlew`: ejecuta `./gradlew clean build jacocoTestReport jacocoTestCoverageVerification`.
   - Falla si la cobertura de líneas global baja del **90%**.
3. Si aún no existe `./gradlew` (antes de la feature F-001), solo corre el paso 1 e informa `BOOTSTRAP`.

## Niveles de prueba

| Nivel | Herramientas | Qué cubre |
|-------|--------------|-----------|
| Unitario de dominio | JUnit 5 | Transiciones de estado, invariantes |
| Unitario de casos de uso | JUnit 5 + Mockito + StepVerifier | Lógica con repositorios simulados |
| Web | `WebTestClient` | Contratos HTTP, códigos de error |
| Integración | Testcontainers (LocalStack) | Adaptadores DynamoDB y SQS reales |
| Concurrencia | `StepVerifier` + `Flux.merge`/`parallel` | N compras simultáneas nunca sobrevenden |

Las pruebas de integración se etiquetan `@Tag("integration")` y requieren Docker; en CI se ejecutan siempre: el workflow define `INCLUDE_INTEGRATION=true`, que `init.sh` traduce a `-PincludeIntegration`. En local, `INCLUDE_INTEGRATION=true ./init.sh` las activa (requiere Docker); sin la variable se excluyen.

## Cobertura

- Mínimo 90% de líneas (JaCoCo). Se excluyen solo clases de arranque (`*Application`) y configuración trivial.
