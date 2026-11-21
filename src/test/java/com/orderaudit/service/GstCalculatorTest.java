package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.GstBreakdown;
import com.orderaudit.domain.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GstCalculatorTest {

    private static final BigDecimal FIVE_PERCENT = new BigDecimal("5");

    /** OFFICIAL: Retailer A (Tamil Nadu): 7,500 + 187.50 CGST + 187.50 SGST = 7,875. */
    @Test
    void retailerAInvoiceSplitsIntoCgstAndSgst() {
        GstBreakdown gst = GstCalculator.calculate(Money.ofRupees("7500"), FIVE_PERCENT, true);
        assertThat(gst.cgst()).isEqualTo(Money.ofRupees("187.50"));
        assertThat(gst.sgst()).isEqualTo(Money.ofRupees("187.50"));
        assertThat(gst.igst()).isEqualTo(Money.ZERO);
        assertThat(gst.invoiceTotal()).isEqualTo(Money.ofRupees("7875"));
    }

    /** Distributor B (Karnataka) would be inter-state. 120 x 50 = 6,000; IGST 300 (derived from official inputs). */
    @Test
    void distributorBInvoiceWouldUseIgst() {
        GstBreakdown gst = GstCalculator.calculate(Money.ofRupees("6000"), FIVE_PERCENT, false);
        assertThat(gst.igst()).isEqualTo(Money.ofRupees("300"));
        assertThat(gst.cgst()).isEqualTo(Money.ZERO);
        assertThat(gst.sgst()).isEqualTo(Money.ZERO);
        assertThat(gst.invoiceTotal()).isEqualTo(Money.ofRupees("6300"));
    }

    @Test
    void oddPaiseTaxNeverLosesAPaise() {
        // 101 paise x 5% = 5.05 -> 5 paise tax; CGST 2, SGST 3
        GstBreakdown gst = GstCalculator.calculate(Money.ofPaise(101), FIVE_PERCENT, true);
        assertThat(gst.cgst().paise()).isEqualTo(2L);
        assertThat(gst.sgst().paise()).isEqualTo(3L);
        assertThat(gst.totalTax().paise()).isEqualTo(5L);
    }

    @Test
    void halfPaiseTaxRoundsUp() {
        // 10 paise x 5% = 0.5 paise -> 1 paise
        GstBreakdown gst = GstCalculator.calculate(Money.ofPaise(10), FIVE_PERCENT, false);
        assertThat(gst.igst().paise()).isEqualTo(1L);
    }

    @Test
    void invoiceTotalIsTheSumOfLineBreakdowns() {
        GstBreakdown line1 = GstCalculator.calculate(Money.ofRupees("7500"), FIVE_PERCENT, true);
        GstBreakdown line2 = GstCalculator.calculate(Money.ofRupees("100"), FIVE_PERCENT, true);
        GstBreakdown invoice = GstBreakdown.ZERO.plus(line1).plus(line2);
        assertThat(invoice.taxableValue()).isEqualTo(Money.ofRupees("7600"));
        assertThat(invoice.totalTax()).isEqualTo(Money.ofRupees("380"));
    }

    @Test
    void placeOfSupplyComparesStateCodes() {
        assertThat(GstCalculator.isIntraState("33", "33")).isTrue();   // Tamil Nadu to Tamil Nadu
        assertThat(GstCalculator.isIntraState("33", "29")).isFalse();  // Tamil Nadu to Karnataka
        assertThatThrownBy(() -> GstCalculator.isIntraState("33", "TN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRatesAreRejected() {
        assertThatThrownBy(() -> GstCalculator.calculate(Money.ZERO, new BigDecimal("101"), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GstCalculator.calculate(Money.ZERO, new BigDecimal("-1"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
