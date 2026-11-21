package com.orderaudit.service;

import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.OpenInvoice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure rule: spread a payment over the oldest unpaid invoices first. Never allocates more than is owed. */
public final class PaymentAllocator {

    private PaymentAllocator() {
    }

    public static List<AllocationRequest> oldestFirst(long amountPaise, List<OpenInvoice> open) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        List<OpenInvoice> ordered = open.stream()
                .filter(i -> i.outstandingPaise() > 0)
                .sorted(Comparator.comparing(OpenInvoice::invoiceDate).thenComparingLong(OpenInvoice::invoiceId))
                .toList();
        List<AllocationRequest> result = new ArrayList<>();
        long remaining = amountPaise;
        for (OpenInvoice invoice : ordered) {
            if (remaining == 0) {
                break;
            }
            long take = Math.min(remaining, invoice.outstandingPaise());
            result.add(new AllocationRequest(invoice.invoiceId(), take));
            remaining -= take;
        }
        if (remaining > 0) {
            throw new IllegalArgumentException("Payment is " + remaining + " paise more than the total outstanding");
        }
        return result;
    }
}
