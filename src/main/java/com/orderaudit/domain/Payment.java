package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    private String reference;

    @Column(name = "received_by", nullable = false)
    private String receivedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(Long customerId, LocalDate paymentDate, long amountPaise, PaymentMethod method,
                   String reference, String receivedBy) {
        this.customerId = customerId;
        this.paymentDate = paymentDate;
        this.amountPaise = amountPaise;
        this.method = method;
        this.reference = reference;
        this.receivedBy = receivedBy;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public long getAmountPaise() { return amountPaise; }
    public PaymentMethod getMethod() { return method; }
    public String getReference() { return reference; }
    public String getReceivedBy() { return receivedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
