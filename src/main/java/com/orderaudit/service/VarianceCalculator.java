package com.orderaudit.service;

import com.orderaudit.domain.Variance;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * variance % = (actual - planned) / planned x 100, shown to 2 decimals.
 * Flagged only if the SIZE of the variance is STRICTLY GREATER than the tolerance ("beyond 3%").
 * The flag is decided with exact arithmetic (no rounded percentage), so exactly 3% is never flagged:
 *     |actual - planned| x 100  >  tolerance x planned
 */
public final class VarianceCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private VarianceCalculator() {
    }

    public static Variance compute(long planned, long actual, BigDecimal tolerancePct) {
        if (planned < 0 || actual < 0) {
            throw new IllegalArgumentException("Quantities must not be negative");
        }
        if (tolerancePct == null || tolerancePct.signum() < 0) {
            throw new IllegalArgumentException("Tolerance must be zero or more");
        }
        if (planned == 0) {
            // Nothing was planned: any consumption at all is a variance we cannot express as a percentage.
            return actual == 0
                    ? new Variance(BigDecimal.ZERO.setScale(2), false)
                    : new Variance(null, true);
        }
        long difference = actual - planned;
        BigDecimal percent = BigDecimal.valueOf(difference).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(planned), 2, RoundingMode.HALF_UP);
        boolean flagged = BigDecimal.valueOf(Math.abs(difference)).multiply(HUNDRED)
                .compareTo(tolerancePct.multiply(BigDecimal.valueOf(planned))) > 0;
        return new Variance(percent, flagged);
    }
}
