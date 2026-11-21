package com.orderaudit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void rupeesConvertToExactPaise() {
        assertThat(Money.ofRupees("7500").paise()).isEqualTo(750_000L);
        assertThat(Money.ofRupees("187.50").paise()).isEqualTo(18_750L);
    }

    @Test
    void moreThanTwoDecimalsIsRejectedNotRounded() {
        assertThatThrownBy(() -> Money.ofRupees("0.005")).isInstanceOf(IllegalArgumentException.class);
    }

    /** OFFICIAL: 150 packets at Rs 50 = Rs 7,500 taxable value. */
    @Test
    void lineTotalForRetailerAOrder() {
        Money total = Money.lineTotal(new BigDecimal("50"), new BigDecimal("150"));
        assertThat(total).isEqualTo(Money.ofRupees("7500"));
    }

    /** DERIVED (not official): flour at weighted average 43.125 per kg x 40 kg = Rs 1,725. */
    @Test
    void lineTotalWithThreeDecimalRateIsExact() {
        Money total = Money.lineTotal(new BigDecimal("43.125"), new BigDecimal("40"));
        assertThat(total).isEqualTo(Money.ofRupees("1725"));
    }

    @Test
    void lineTotalRoundsExactHalfPaiseUp() {
        assertThat(Money.lineTotal(new BigDecimal("0.005"), BigDecimal.ONE).paise()).isEqualTo(1L);
        assertThat(Money.lineTotal(new BigDecimal("0.004"), BigDecimal.ONE).paise()).isEqualTo(0L);
    }

    /** OFFICIAL: Retailer A pays Rs 4,000 of Rs 7,875, leaving Rs 3,875. */
    @Test
    void outstandingForRetailerA() {
        Money outstanding = Money.ofRupees("7875").minus(Money.ofRupees("4000"));
        assertThat(outstanding).isEqualTo(Money.ofRupees("3875"));
    }

    @Test
    void displaysRupeesWithTwoDecimals() {
        assertThat(Money.ofPaise(787_500L).toString()).isEqualTo("7875.00");
        assertThat(Money.ofPaise(5L).toRupees()).isEqualByComparingTo("0.05");
    }

    @Test
    void overflowIsDetectedNotWrapped() {
        assertThatThrownBy(() -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)))
                .isInstanceOf(ArithmeticException.class);
    }
}
