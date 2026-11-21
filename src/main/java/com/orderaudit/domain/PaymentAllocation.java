package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_allocation")
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    protected PaymentAllocation() {
    }

    public PaymentAllocation(Long paymentId, Long invoiceId, long amountPaise) {
        this.paymentId = paymentId;
        this.invoiceId = invoiceId;
        this.amountPaise = amountPaise;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public Long getInvoiceId() { return invoiceId; }
    public long getAmountPaise() { return amountPaise; }
}
