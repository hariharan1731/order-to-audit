package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CreditNoteNumberTest {

    @Test
    void creditNoteNumbersFollowTheFinancialYear() {
        assertThat(FinancialYear.creditNoteNumber("2026-27", 1)).isEqualTo("CN/2026-27/0001");
        assertThat(FinancialYear.creditNoteNumber("2026-27", 123)).isEqualTo("CN/2026-27/0123");
    }

    @Test
    void sequenceStartsAtOne() {
        assertThatThrownBy(() -> FinancialYear.creditNoteNumber("2026-27", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
