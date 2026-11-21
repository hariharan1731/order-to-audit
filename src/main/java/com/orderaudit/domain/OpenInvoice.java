package com.orderaudit.domain;

import java.time.LocalDate;

/** An invoice with how much has been paid and how much is still owed (all paise). */
public record OpenInvoice(long invoiceId, String invoiceNumber, LocalDate invoiceDate,
                          long totalPaise, long paidPaise, long outstandingPaise) {
}
