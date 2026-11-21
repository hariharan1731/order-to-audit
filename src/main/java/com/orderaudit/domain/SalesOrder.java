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

/**
 * An order and its life cycle. The status only moves forward through the methods below,
 * and each method refuses to run from the wrong status, so an order can never skip a step.
 */
@Entity
@Table(name = "sales_order")
public class SalesOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private String salesman;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.SUBMITTED;

    @Column(name = "client_request_id")
    private String clientRequestId;

    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "dispatched_by")
    private String dispatchedBy;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    protected SalesOrder() {
    }

    public SalesOrder(Long customerId, String salesman, String clientRequestId, String note) {
        this.customerId = customerId;
        this.salesman = salesman;
        this.clientRequestId = clientRequestId;
        this.note = note;
    }

    private void require(OrderStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("Cannot " + action + " an order that is " + status
                    + " (it must be " + expected + ")");
        }
    }

    public void approve(String by) {
        require(OrderStatus.SUBMITTED, "approve");
        status = OrderStatus.APPROVED;
        decidedBy = by;
        decidedAt = Instant.now();
    }

    public void reject(String by, String reason) {
        require(OrderStatus.SUBMITTED, "reject");
        status = OrderStatus.REJECTED;
        decidedBy = by;
        decidedAt = Instant.now();
        rejectReason = reason;
    }

    public void markInvoiced() {
        require(OrderStatus.APPROVED, "invoice");
        status = OrderStatus.INVOICED;
    }

    public void markDispatched(String by) {
        require(OrderStatus.INVOICED, "dispatch");
        status = OrderStatus.DISPATCHED;
        dispatchedBy = by;
        dispatchedAt = Instant.now();
    }

    /** Lines may only be edited before approval. */
    public void requireEditable() {
        require(OrderStatus.SUBMITTED, "edit");
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getSalesman() { return salesman; }
    public OrderStatus getStatus() { return status; }
    public String getClientRequestId() { return clientRequestId; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getRejectReason() { return rejectReason; }
    public String getDispatchedBy() { return dispatchedBy; }
    public Instant getDispatchedAt() { return dispatchedAt; }
}
