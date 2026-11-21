package com.orderaudit.service;

import com.orderaudit.domain.StockCheckLine;
import java.util.List;

/** Thrown when an order cannot be approved because finished-goods stock does not cover it. */
public class ShortageException extends RuntimeException {

    private final List<StockCheckLine> shortages;

    public ShortageException(List<StockCheckLine> shortages) {
        super("Not enough stock: " + shortages.stream()
                .map(l -> "item " + l.itemId() + " short by " + l.shortage()
                        + " (asked " + l.requested() + ", available " + l.available() + ")")
                .reduce((a, b) -> a + "; " + b).orElse(""));
        this.shortages = List.copyOf(shortages);
    }

    public List<StockCheckLine> getShortages() {
        return shortages;
    }
}
