package com.orderaudit.domain;

/** What a salesman asks for: an item and a number of packets. The price is looked up, never typed by the salesman. */
public record OrderLineRequest(long itemId, long quantity) {
}
