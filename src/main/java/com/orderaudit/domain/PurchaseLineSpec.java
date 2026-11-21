package com.orderaudit.domain;

import java.math.BigDecimal;

/** One line of a purchase: item, quantity in base units, cost in paise per base unit. */
public record PurchaseLineSpec(long itemId, long quantityBase, BigDecimal unitCostPaise) {
}
