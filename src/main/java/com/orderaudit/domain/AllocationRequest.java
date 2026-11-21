package com.orderaudit.domain;

/** Part of a payment assigned to one invoice, in paise. */
public record AllocationRequest(long invoiceId, long amountPaise) {
}
