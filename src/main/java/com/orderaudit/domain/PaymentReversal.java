package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** "This payment no longer counts." Append-only: the payment itself is never edited or deleted. */
@Entity
@Table(name = "payment_reversal")
public class PaymentReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "reversal_date", nullable = false)
    private LocalDate reversalDate;

    @Column(nullable = false)
    private String reason;

    @Column(name = "reversed_by", nullable = false)
    private String reversedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected PaymentReversal() {
    }

    public PaymentReversal(Long paymentId, LocalDate reversalDate, String reason, String reversedBy) {
        this.paymentId = paymentId;
        this.reversalDate = reversalDate;
        this.reason = reason;
        this.reversedBy = reversedBy;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public LocalDate getReversalDate() { return reversalDate; }
    public String getReason() { return reason; }
    public String getReversedBy() { return reversedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
