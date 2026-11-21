package com.orderaudit.domain;

/**
 * Can stock cover one order line? available = on hand - reserved by other approved/invoiced orders.
 * shortage = how many packets are missing (0 if covered).
 */
public record StockCheckLine(long itemId, long requested, long onHand, long reserved, long available, long shortage) {

    public boolean isShort() {
        return shortage > 0;
    }
}
