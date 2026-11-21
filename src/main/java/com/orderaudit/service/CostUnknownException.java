package com.orderaudit.service;

/** Thrown when a run needs the cost of an item that has never been received with a cost. */
public class CostUnknownException extends RuntimeException {
    public CostUnknownException(long itemId) {
        super("No cost is known for item " + itemId + ": record its opening stock or a purchase with a cost first");
    }
}
