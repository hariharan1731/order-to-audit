package com.orderaudit.domain;

/** Planned consumption of one item for a batch, in base units. */
public record PlannedLine(long itemId, PlannedSource source, long plannedQuantity) {
}
