package com.orderaudit.domain;

import java.util.EnumSet;
import java.util.Set;

/** What kind of thing an item is, and which base units are allowed for that kind. */
public enum ItemType {
    RAW(EnumSet.of(Unit.GRAM, Unit.MILLILITRE)),   // flour in grams, oil in millilitres
    PACKAGING(EnumSet.of(Unit.PIECE)),             // pouches and cartons, counted in pieces
    FINISHED(EnumSet.of(Unit.PACKET));             // finished goods, counted in packets

    private final Set<Unit> allowedBaseUnits;

    ItemType(Set<Unit> allowedBaseUnits) {
        this.allowedBaseUnits = allowedBaseUnits;
    }

    public boolean allows(Unit unit) {
        return allowedBaseUnits.contains(unit);
    }

    public Set<Unit> allowedBaseUnits() {
        return allowedBaseUnits;
    }
}
