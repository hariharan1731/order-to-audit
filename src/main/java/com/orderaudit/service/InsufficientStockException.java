package com.orderaudit.service;

/** Thrown when an issue or sale needs more stock than is available. */
public class InsufficientStockException extends RuntimeException {

    private final long itemId;
    private final long available;
    private final long requested;

    public InsufficientStockException(long itemId, long available, long requested) {
        super("Insufficient stock for item " + itemId + ": available " + available + ", requested " + requested);
        this.itemId = itemId;
        this.available = available;
        this.requested = requested;
    }

    public long getItemId() { return itemId; }
    public long getAvailable() { return available; }
    public long getRequested() { return requested; }
}
