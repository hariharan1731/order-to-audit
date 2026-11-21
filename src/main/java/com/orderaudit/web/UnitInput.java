package com.orderaudit.web;

import com.orderaudit.domain.Unit;
import com.orderaudit.service.CostMath;
import java.math.BigDecimal;

/**
 * Staff type kilograms and litres, the database stores grams and millilitres (whole numbers).
 * All the conversion lives here, exact, so no quantity is ever a rounded fraction.
 */
public final class UnitInput {

    private UnitInput() {
    }

    /** The unit people type in: kg for grams, L for millilitres, the same unit otherwise. */
    public static Unit displayUnit(Unit baseUnit) {
        return switch (baseUnit) {
            case GRAM -> Unit.KILOGRAM;
            case MILLILITRE -> Unit.LITRE;
            default -> baseUnit;
        };
    }

    public static String label(Unit baseUnit) {
        return switch (displayUnit(baseUnit)) {
            case KILOGRAM -> "kg";
            case LITRE -> "L";
            case PIECE -> "pcs";
            case PACKET -> "packets";
            default -> throw new IllegalArgumentException(baseUnit + " is not a base unit");
        };
    }

    /** 40000 g -> "40", 1200 g -> "1.2" (plain text for an input box). */
    public static String toDisplay(long baseQuantity, Unit baseUnit) {
        long factor = displayUnit(baseUnit).baseFactor();
        BigDecimal value = new BigDecimal(baseQuantity).divide(BigDecimal.valueOf(factor));
        return value.stripTrailingZeros().toPlainString();
    }

    /** "1.2" kg -> 1200 g. Fails if the result is not a whole number of base units (e.g. 0.0005 kg). */
    public static long toBase(String typed, Unit baseUnit) {
        BigDecimal value;
        try {
            value = new BigDecimal(typed.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("'" + typed + "' is not a number");
        }
        try {
            return value.multiply(BigDecimal.valueOf(displayUnit(baseUnit).baseFactor())).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("'" + typed + "' has too many decimals for " + label(baseUnit));
        }
    }

    /** Rs 45 per kg -> 4.5 paise per gram. */
    public static BigDecimal ratePerBaseUnit(String rupeesPerDisplayUnit, Unit baseUnit) {
        BigDecimal rate;
        try {
            rate = new BigDecimal(rupeesPerDisplayUnit.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("'" + rupeesPerDisplayUnit + "' is not a valid rate");
        }
        return CostMath.paisePerBaseUnit(rate, displayUnit(baseUnit));
    }
}
