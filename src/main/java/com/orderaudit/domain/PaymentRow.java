package com.orderaudit.domain;

import java.time.LocalDate;

/** One payment as shown on the customer's payment page, with its reversal (if any). Amounts in paise. */
public record PaymentRow(long paymentId, LocalDate paymentDate, long amountPaise, PaymentMethod method,
                         String reference, String receivedBy, String invoices,
                         boolean reversed, LocalDate reversalDate, String reversalReason, String reversedBy) {
}
