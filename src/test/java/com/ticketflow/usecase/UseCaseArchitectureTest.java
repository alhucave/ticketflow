package com.ticketflow.usecase;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class UseCaseArchitectureTest {

    private static final JavaClasses USECASE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ticketflow.usecase");

    @Test
    void usecase_hasClassesToVerify() {
        assertThat(USECASE.size()).isPositive();
    }

    @Test
    void usecase_doesNotDependOnInfrastructureWebOrAws() {
        noClasses().that().resideInAPackage("com.ticketflow.usecase..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ticketflow.infrastructure..", "org.springframework..",
                        "software.amazon..", "com.amazonaws..")
                .check(USECASE);
    }
}
