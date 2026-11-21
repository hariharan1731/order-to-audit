package com.orderaudit.domain;

import java.math.BigDecimal;

/** The few facts about a ledger row that costing needs. unitCost and reversesId may be null. */
public record CostMovement(long id, long qtySigned, BigDecimal unitCost, Long reversesId) {
}
