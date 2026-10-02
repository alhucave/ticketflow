# Informe F-001 — gradle-bootstrap

## Archivos
Creados: `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`,
`src/main/java/com/ticketflow/TicketflowApplication.java`,
`src/main/java/com/ticketflow/{domain,usecase,infrastructure}/package-info.java`,
`src/main/resources/application.yml`, `src/test/java/com/ticketflow/HealthEndpointTest.java`,
`progress/impl_gradle-bootstrap.md`.
Modificados: `feature_list.json` (F-001 -> in_progress), `progress/current.md`.
`.gitignore` ya contenia build/, .gradle/, .env, .idea/, *.iml, .vscode/ (sin cambios necesarios).
`init.sh` sin cambios: ya ejecuta el build cuando existe `./gradlew`.

## Versiones (verificadas por red el 2026-10-02)
- Spring Boot plugin: 4.1.1 (ultima estable 4.x en Maven Central, `spring-boot-gradle-plugin/maven-metadata.xml`; 4.2.0-M1/M2 son milestones, descartadas).
- Gradle: 9.8.0 (services.gradle.org/versions/current, checksum SHA-256 verificado; 9.x soporta Java 25).
- AWS SDK v2 BOM: 2.55.10 (Maven Central `software/amazon/awssdk/bom`, release).
- Testcontainers: 2.0.5 (gestionada por el BOM de Spring Boot 4.1.1; coincide con la ultima release en Central). En 2.x los artefactos son `testcontainers-junit-jupiter` y `testcontainers-localstack`.
- JDK: Temurin 25.0.4.1.
- Starters de test Boot 4: `spring-boot-starter-webflux-test` y `spring-boot-starter-actuator-test` existen en 4.x; `@AutoConfigureWebTestClient` esta en `org.springframework.boot.webtestclient.autoconfigure` (modulo spring-boot-webtestclient). Compila y el test pasa.

## Decisiones
- Cobertura: JaCoCo con regla LINE COVEREDRATIO >= 0.90, `jacocoTestCoverageVerification` enganchado a `check` (y por tanto a `build`). Se excluye `*Application*` (docs/verification.md). Verificado que falla: con una clase temporal sin cubrir el build falla con "lines covered ratio is 0.00, but expected minimum is 0.90"; la clase se elimino despues.
- Con el esqueleto actual no hay lineas medibles (package-info y Application excluida), la regla pasa vacia; se vuelve efectiva en cuanto exista codigo.
- Tests de integracion: `@Tag("integration")` se excluyen por defecto (no requieren Docker en local); se activan con `-PincludeIntegration`. CI (F-003) debera pasarlo. Dependencias Testcontainers ya incluidas.
- Actuator expone solo `health`.
- Mockito via `mockito-core` (spring-boot-starter-webflux-test ya trae starter-test).
- Wrapper generado con una distribucion Gradle 9.8.0 descargada temporalmente (no instalada en el sistema).

## Salida de ./init.sh
```
==> Validating feature_list.json
OK: 26 features, in_progress=['F-001']
==> Building and verifying (tests + 90% coverage gate)
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/9.8.0/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :clean
> Task :compileJava
> Task :processResources
> Task :classes
> Task :resolveMainClassName
> Task :bootJar
> Task :jar
> Task :assemble
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
> Task :test
> Task :jacocoTestCoverageVerification
> Task :check
> Task :build
> Task :jacocoTestReport

BUILD SUCCESSFUL in 12s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.8.0/userguide/configuration_cache_enabling.html
==> init.sh OK
```
