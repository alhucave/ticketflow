plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
}

group = "com.ticketflow"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Reproducible, scannable dependency graph: versions are pinned in gradle.lockfile (lockAllConfigurations).
// Update flow: ./gradlew dependencies --write-locks (see docs/security.md); the build fails on a stale lock.
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("software.amazon.awssdk:bom:2.55.10"))
    // Security override of the Jackson 3.1.x line managed by Spring Boot 4.1.1 (3.1.5): fixes the fixable HIGH CVEs
    // found by Trivy (jackson-core/databind DoS). Gradle picks the highest platform version. Remove it once the
    // Spring Boot BOM ships Jackson >= 3.1.7.
    implementation(platform("tools.jackson:jackson-bom:3.1.7"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Carries the correlation id from the Reactor context into the logging MDC (see CorrelationContextPropagation).
    implementation("io.micrometer:context-propagation")
    // Bounded, expiring per-client state of the rate limiter (version managed by the Spring Boot BOM).
    implementation("com.github.ben-manes.caffeine:caffeine")
    // Prometheus scrape endpoint (/actuator/prometheus on the management port). Version managed by the Micrometer BOM
    // imported through the Spring Boot BOM (Prometheus client 1.x; the *-simpleclient registry is deprecated).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:sqs")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-localstack")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Puts the project version in application.yml (@projectVersion@): it becomes the service.version of the JSON logs.
tasks.processResources {
    filesMatching("application.yml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("projectVersion" to project.version.toString()))
    }
}

tasks.test {
    useJUnitPlatform {
        // Integration tests need Docker; they are enabled with -PincludeIntegration (always in CI).
        if (!project.hasProperty("includeIntegration")) {
            excludeTags("integration")
        }
    }
    finalizedBy(tasks.jacocoTestReport)
}

val coverageExcludes = listOf("**/*Application*.class")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude(coverageExcludes) }
    }))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude(coverageExcludes) }
    }))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
