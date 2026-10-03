package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.infrastructure.web.error.AdminAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminKeyGuardTest {

    private final AdminKeyGuard guard = new AdminKeyGuard("s3cret-value");

    @Test
    void check_correctKey_passes() {
        assertThat(guard.enabled()).isTrue();
        assertThatCode(() -> guard.check("s3cret-value")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "s3cret-valu", "s3cret-value ", "S3CRET-VALUE", ""})
    void check_wrongKey_isUnauthorized(String supplied) {
        assertThatThrownBy(() -> guard.check(supplied)).isInstanceOfSatisfying(AdminAccessDeniedException.class,
                e -> assertThat(e.isDisabled()).isFalse());
    }

    @Test
    void check_missingKey_isUnauthorized() {
        assertThatThrownBy(() -> guard.check(null)).isInstanceOfSatisfying(AdminAccessDeniedException.class,
                e -> assertThat(e.isDisabled()).isFalse());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void check_noKeyConfigured_isDisabledEvenForEmptyOrMatchingInput(String configured) {
        var disabled = new AdminKeyGuard(configured);
        assertThat(disabled.enabled()).isFalse();
        for (String supplied : new String[] {null, "", configured, "anything"}) {
            assertThatThrownBy(() -> disabled.check(supplied)).isInstanceOfSatisfying(AdminAccessDeniedException.class,
                    e -> assertThat(e.isDisabled()).isTrue());
        }
    }

    @Test
    void messages_neverEchoTheKeyOrTheSuppliedValue() {
        assertThatThrownBy(() -> guard.check("attacker-guess")).hasMessageNotContaining("attacker-guess")
                .hasMessageNotContaining("s3cret-value");
    }
}
