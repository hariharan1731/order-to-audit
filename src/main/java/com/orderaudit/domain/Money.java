package com.orderaudit.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of rupees stored as a whole number of paise (1 rupee = 100 paise).
 * Whole numbers are exact, so there are no floating-point surprises.
 * A record is a short Java way to write an immutable class with one field.
 */
public record Money(long paise) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    /** Exact rupees such as "7500.50". More than 2 decimals is rejected, never silently rounded. */
    public static Money ofRupees(String rupees) {
        return ofRupees(new BigDecimal(rupees));
    }

    public static Money ofRupees(BigDecimal rupees) {
        try {
            return new Money(rupees.movePointRight(2).longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount has more than 2 decimal places: " + rupees.toPlainString(), e);
        }
    }

    /**
     * Business rule: rounding happens ONLY here, on a line total, to the nearest paise,
     * with exact halves rounded up. Rates may have many decimals (e.g. 43.125 per kg).
     */
    public static Money lineTotal(BigDecimal unitRateRupees, BigDecimal quantity) {
        BigDecimal paise = unitRateRupees.multiply(quantity)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP);
        return new Money(paise.longValueExact());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise)); // throws if the number overflows
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    public Money negate() {
        return new Money(Math.negateExact(paise));
    }

    public boolean isNegative() {
        return paise < 0;
    }

    /** Rupees with exactly 2 decimals, for display and CSV, e.g. 787500 paise -> 7875.00 */
    public BigDecimal toRupees() {
        return BigDecimal.valueOf(paise, 2);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(paise, other.paise);
    }

    @Override
    public String toString() {
        return toRupees().toPlainString();
    }
}
