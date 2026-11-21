package com.orderaudit.service;

import com.orderaudit.domain.GstBreakdown;
import com.orderaudit.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GST rules. Pure calculation: no database, no Spring.
 * Rule 1: if supplier state == customer state, tax is split into CGST + SGST; otherwise IGST.
 * Rule 2: tax is rounded to the nearest paise (halves up) per line; invoice totals are sums of lines.
 * Rule 3: CGST = half of the tax (rounded down); SGST = tax - CGST, so no paise is ever lost.
 */
public final class GstCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private GstCalculator() {
    }

    /** Compare 2-digit GST state codes (Tamil Nadu = 33, Karnataka = 29). Never guess from names. */
    public static boolean isIntraState(String factoryStateCode, String customerStateCode) {
        requireStateCode(factoryStateCode);
        requireStateCode(customerStateCode);
        return factoryStateCode.equals(customerStateCode);
    }

    public static GstBreakdown calculate(Money taxableValue, BigDecimal ratePercent, boolean intraState) {
        if (ratePercent.signum() < 0 || ratePercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("GST rate must be between 0 and 100: " + ratePercent);
        }
        long taxPaise = BigDecimal.valueOf(taxableValue.paise())
                .multiply(ratePercent)
                .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                .longValueExact();
        if (intraState) {
            long cgst = taxPaise / 2;
            long sgst = taxPaise - cgst;
            return new GstBreakdown(taxableValue, Money.ofPaise(cgst), Money.ofPaise(sgst), Money.ZERO);
        }
        return new GstBreakdown(taxableValue, Money.ZERO, Money.ZERO, Money.ofPaise(taxPaise));
    }

    private static void requireStateCode(String code) {
        if (code == null || !code.matches("\\d{2}")) {
            throw new IllegalArgumentException("State code must be 2 digits: " + code);
        }
    }
}
