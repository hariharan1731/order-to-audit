package com.orderaudit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FormatsTest {

    private final Formats fmt = new Formats();

    @Test
    void rupeesAreExactWithTwoDecimalsAndSeparators() {
        assertThat(fmt.rupees(787_500)).isEqualTo("Rs 7,875.00");
        assertThat(fmt.rupees(5)).isEqualTo("Rs 0.05");
        assertThat(fmt.rupees(0)).isEqualTo("Rs 0.00");
        assertThat(fmt.rupees(123_456_789)).isEqualTo("Rs 1,234,567.89");
    }

    @Test
    void negativeAmountsKeepTheirSign() {
        assertThat(fmt.rupees(-787_500)).isEqualTo("Rs -7,875.00");
    }

    @Test
    void plainRupeesHaveNoLabelOrSeparators() {
        assertThat(fmt.plainRupees(5_000)).isEqualTo("50.00");
        assertThat(fmt.plainRupees(123_456_789)).isEqualTo("1234567.89");
    }

    @Test
    void numbersGetThousandsSeparators() {
        assertThat(fmt.number(1_234_567)).isEqualTo("1,234,567");
    }

    @Test
    void datesAreShownInIndianTimeAndNullsAreBlank() {
        assertThat(fmt.dateTime(Instant.parse("2026-11-21T10:00:00Z"))).isEqualTo("21 Nov 2026, 15:30");
        assertThat(fmt.date(LocalDate.of(2026, 11, 21))).isEqualTo("21 Nov 2026");
        assertThat(fmt.dateTime(null)).isEmpty();
        assertThat(fmt.date(null)).isEmpty();
    }

    @Test
    void everyStatusHasItsOwnIcon() {
        assertThat(fmt.statusIcon("SUBMITTED")).isEqualTo("bi-hourglass-split");
        assertThat(fmt.statusIcon("DISPATCHED")).isEqualTo("bi-truck");
        assertThat(fmt.statusIcon("REJECTED")).isEqualTo("bi-x-circle");
        assertThat(fmt.statusIcon(com.orderaudit.domain.OrderStatus.APPROVED)).isEqualTo("bi-check2-circle");
        assertThat(fmt.statusIcon(null)).isEqualTo("bi-dot");
        // invoice statuses use the same shared badge, so they get their own icons too
        assertThat(fmt.statusIcon(com.orderaudit.domain.InvoiceStatus.ISSUED)).isEqualTo("bi-file-earmark-check");
        assertThat(fmt.statusIcon("CANCELLED")).isEqualTo("bi-x-octagon");
    }
}
