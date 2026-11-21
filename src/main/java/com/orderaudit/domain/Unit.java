package com.orderaudit.domain;

import java.math.BigDecimal;

/**
 * Units of measure. Each unit belongs to a dimension and knows how many base units it holds.
 * Base units: gram (mass), millilitre (volume), piece, packet.
 * Stock is always stored in base units as whole numbers, e.g. 40 kg = 40,000 g.
 * Cartons are NOT a unit here: packets-per-carton belongs to a product (see CartonPacking).
 */
public enum Unit {
    GRAM(Dimension.MASS, 1),
    KILOGRAM(Dimension.MASS, 1000),
    MILLILITRE(Dimension.VOLUME, 1),
    LITRE(Dimension.VOLUME, 1000),
    PIECE(Dimension.PIECE, 1),
    PACKET(Dimension.PACKET, 1);

    public enum Dimension { MASS, VOLUME, PIECE, PACKET }

    private final Dimension dimension;
    private final long baseFactor;

    Unit(Dimension dimension, long baseFactor) {
        this.dimension = dimension;
        this.baseFactor = baseFactor;
    }

    public Dimension dimension() {
        return dimension;
    }

    public long baseFactor() {
        return baseFactor;
    }

    public Unit baseUnit() {
        return switch (dimension) {
            case MASS -> GRAM;
            case VOLUME -> MILLILITRE;
            case PIECE -> PIECE;
            case PACKET -> PACKET;
        };
    }

    /** Quantity in this unit -> whole base units. 10.5 L -> 10500 ml. Fractions of a base unit are rejected. */
    public long toBaseUnits(BigDecimal quantity) {
        try {
            return quantity.multiply(BigDecimal.valueOf(baseFactor)).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    quantity.toPlainString() + " " + this + " is not a whole number of "
                            + baseUnit() + "s", e);
        }
    }

    /** Convert between two units of the same dimension. kg -> litre throws UnitMismatchException. */
    public BigDecimal convertTo(BigDecimal quantity, Unit target) {
        if (dimension != target.dimension) {
            throw new UnitMismatchException("Cannot convert " + this + " to " + target);
        }
        return quantity.multiply(BigDecimal.valueOf(baseFactor))
                .divide(BigDecimal.valueOf(target.baseFactor)); // factors are 1 or 1000, so exact
    }
}
