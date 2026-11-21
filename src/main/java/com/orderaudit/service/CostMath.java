package com.orderaudit.service;

import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cost arithmetic. Rates are PAISE per base unit with 4 decimals; totals are whole paise.
 * Rounding happens in exactly three places and nowhere else:
 *   - converting a rupee rate to paise per base unit (4 decimals, half up)
 *   - a line cost: quantity x rate, rounded to whole paise (half up)
 *   - cost per packet: total / good packets, 4 decimals (half up)
 */
public final class CostMath {

    private CostMath() {
    }

    /** Rs 45 per kg -> 4.5 paise per gram. Rs 140 per litre -> 14 paise per ml. */
    public static BigDecimal paisePerBaseUnit(BigDecimal rupeesPerUnit, Unit unit) {
        if (rupeesPerUnit == null || rupeesPerUnit.signum() < 0) {
            throw new IllegalArgumentException("Rate must be zero or more");
        }
        return rupeesPerUnit.movePointRight(2)
                .divide(BigDecimal.valueOf(unit.baseFactor()), 4, RoundingMode.HALF_UP);
    }

    /** quantity (base units) x rate (paise per base unit) -> whole paise. */
    public static long lineCostPaise(long quantityBase, BigDecimal paisePerBaseUnit) {
        return BigDecimal.valueOf(quantityBase).multiply(paisePerBaseUnit)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Total run cost spread over good packets only. Damaged pouches and overuse are absorbed by good output. */
    public static BigDecimal costPerPacket(long totalCostPaise, long goodPackets) {
        if (goodPackets <= 0) {
            throw new IllegalArgumentException("Cost per packet needs at least one good packet");
        }
        return BigDecimal.valueOf(totalCostPaise)
                .divide(BigDecimal.valueOf(goodPackets), 4, RoundingMode.HALF_UP);
    }

    public static long roundToPaise(BigDecimal paise) {
        return paise.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
