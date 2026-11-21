package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void aReasonablePasswordIsAccepted() {
        assertThatCode(() -> PasswordPolicy.check("murukku2026", "admin")).doesNotThrowAnyException();
    }

    @Test
    void tooShortIsRejected() {
        assertThatThrownBy(() -> PasswordPolicy.check("abc1234", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 8");
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> PasswordPolicy.check(null, "admin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooLongForBcryptIsRejected() {
        assertThatThrownBy(() -> PasswordPolicy.check("a1".repeat(40), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 72");
    }

    @Test
    void needsBothALetterAndADigit() {
        assertThatThrownBy(() -> PasswordPolicy.check("abcdefghij", "admin")).hasMessageContaining("letter and one digit");
        assertThatThrownBy(() -> PasswordPolicy.check("1234567890", "admin")).hasMessageContaining("letter and one digit");
    }

    @Test
    void cannotEqualTheUsername() {
        assertThatThrownBy(() -> PasswordPolicy.check("Admin1234", "admin1234"))
                .hasMessageContaining("same as the username");
    }
}
