package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinancialYearTest {

    @Test
    void aprilStartsANewFinancialYear() {
        assertThat(FinancialYear.label(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(FinancialYear.label(LocalDate.of(2026, 11, 21))).isEqualTo("2026-27");
    }

    @Test
    void januaryToMarchBelongToThePreviousStartYear() {
        assertThat(FinancialYear.label(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(FinancialYear.label(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(FinancialYear.label(LocalDate.of(2027, 1, 1))).isEqualTo("2026-27");
    }

    @Test
    void theTwoDigitEndYearWrapsAtTheCentury() {
        assertThat(FinancialYear.label(LocalDate.of(2099, 6, 1))).isEqualTo("2099-00");
        assertThat(FinancialYear.label(LocalDate.of(2009, 6, 1))).isEqualTo("2009-10");
    }

    @Test
    void invoiceNumbersArePaddedToFourDigits() {
        assertThat(FinancialYear.invoiceNumber("2026-27", 1)).isEqualTo("INV/2026-27/0001");
        assertThat(FinancialYear.invoiceNumber("2026-27", 42)).isEqualTo("INV/2026-27/0042");
        assertThat(FinancialYear.invoiceNumber("2026-27", 12345)).isEqualTo("INV/2026-27/12345");
    }

    @Test
    void theSequenceStartsAtOne() {
        assertThatThrownBy(() -> FinancialYear.invoiceNumber("2026-27", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
