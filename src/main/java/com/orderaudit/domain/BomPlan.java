package com.orderaudit.domain;

import java.util.List;

/** The planned side of a production run: expected good packets and every material to consume. */
public record BomPlan(long expectedPackets, List<PlannedLine> lines) {

    public long quantityOf(long itemId) {
        return lines.stream()
                .filter(line -> line.itemId() == itemId)
                .mapToLong(PlannedLine::plannedQuantity)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item " + itemId + " is not in this plan"));
    }
}
