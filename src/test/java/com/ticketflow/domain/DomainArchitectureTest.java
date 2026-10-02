package com.ticketflow.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class DomainArchitectureTest {

    private static final JavaClasses DOMAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ticketflow.domain");

    @Test
    void domain_hasClassesToVerify() {
        org.assertj.core.api.Assertions.assertThat(DOMAIN.size()).isPositive();
    }

    @Test
    void domain_doesNotDependOnSpringOrAws() {
        noClasses().that().resideInAPackage("com.ticketflow.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "software.amazon..", "com.amazonaws..")
                .check(DOMAIN);
    }

    @Test
    void domain_doesNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("com.ticketflow.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ticketflow.usecase..", "com.ticketflow.infrastructure..")
                .check(DOMAIN);
    }

    @Test
    void modelAndExceptions_doNotDependOnReactor() {
        noClasses().that().resideInAnyPackage("com.ticketflow.domain.model..", "com.ticketflow.domain.exception..")
                .should().dependOnClassesThat().resideInAPackage("reactor..")
                .check(DOMAIN);
    }

    @Test
    void ports_areInterfaces() {
        classes().that().resideInAPackage("com.ticketflow.domain.port..")
                .should().beInterfaces()
                .check(DOMAIN);
    }
}
