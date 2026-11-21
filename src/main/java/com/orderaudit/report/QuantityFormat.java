package com.orderaudit.report;

import com.orderaudit.domain.Unit;
import java.math.BigDecimal;

/** Human-friendly quantities: 120000 g -> "120 kg", 19500 ml -> "19.5 L", 306 pieces -> "306 pcs". */
public final class QuantityFormat {

    private QuantityFormat() {
    }

    public static String display(long baseQuantity, Unit baseUnit) {
        return switch (baseUnit) {
            case GRAM -> thousandths(baseQuantity) + " kg";
            case MILLILITRE -> thousandths(baseQuantity) + " L";
            case PIECE -> baseQuantity + " pcs";
            case PACKET -> baseQuantity + " packets";
            default -> throw new IllegalArgumentException(baseUnit + " is not a base unit");
        };
    }

    private static String thousandths(long baseQuantity) {
        BigDecimal value = BigDecimal.valueOf(baseQuantity, 3).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    /** The unit label used next to raw base-unit numbers: g, ml, pcs, packets. */
    public static String baseUnitLabel(Unit baseUnit) {
        return switch (baseUnit) {
            case GRAM -> "g";
            case MILLILITRE -> "ml";
            case PIECE -> "pcs";
            case PACKET -> "packets";
            default -> throw new IllegalArgumentException(baseUnit + " is not a base unit");
        };
    }
}
