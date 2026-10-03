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

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("software.amazon.awssdk:bom:2.55.10"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Carries the correlation id from the Reactor context into the logging MDC (see CorrelationContextPropagation).
    implementation("io.micrometer:context-propagation")
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
