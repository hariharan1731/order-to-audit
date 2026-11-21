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

/** A GST tax invoice. A snapshot: no setters, so a posted invoice can never be edited. */
@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false)
    private String invoiceNumber;

    @Column(name = "fy_label", nullable = false)
    private String fyLabel;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_gstin")
    private String customerGstin;

    @Column(name = "customer_state", nullable = false)
    private String customerState;

    @Column(name = "supply_state", nullable = false)
    private String supplyState;

    @Column(name = "intra_state", nullable = false)
    private boolean intraState;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "taxable_paise", nullable = false)
    private long taxablePaise;

    @Column(name = "cgst_paise", nullable = false)
    private long cgstPaise;

    @Column(name = "sgst_paise", nullable = false)
    private long sgstPaise;

    @Column(name = "igst_paise", nullable = false)
    private long igstPaise;

    @Column(name = "total_paise", nullable = false)
    private long totalPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Invoice() {
    }

    public Invoice(String invoiceNumber, String fyLabel, Long orderId, Customer customer, String supplyState,
                   boolean intraState, LocalDate invoiceDate, GstBreakdown totals, String createdBy) {
        this.invoiceNumber = invoiceNumber;
        this.fyLabel = fyLabel;
        this.orderId = orderId;
        this.customerId = customer.getId();
        this.customerName = customer.getName();
        this.customerGstin = customer.getGstin();
        this.customerState = customer.getStateCode();
        this.supplyState = supplyState;
        this.intraState = intraState;
        this.invoiceDate = invoiceDate;
        this.taxablePaise = totals.taxableValue().paise();
        this.cgstPaise = totals.cgst().paise();
        this.sgstPaise = totals.sgst().paise();
        this.igstPaise = totals.igst().paise();
        this.totalPaise = totals.invoiceTotal().paise();
        this.createdBy = createdBy;
    }

    /** An issued invoice can only be cancelled (by a credit note), never deleted or edited. */
    public void cancel() {
        if (status != InvoiceStatus.ISSUED) {
            throw new IllegalStateException("Invoice " + invoiceNumber + " is already " + status);
        }
        this.status = InvoiceStatus.CANCELLED;
    }

    public Long getId() { return id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getFyLabel() { return fyLabel; }
    public Long getOrderId() { return orderId; }
    public Long getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getCustomerGstin() { return customerGstin; }
    public String getCustomerState() { return customerState; }
    public String getSupplyState() { return supplyState; }
    public boolean isIntraState() { return intraState; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public long getTaxablePaise() { return taxablePaise; }
    public long getCgstPaise() { return cgstPaise; }
    public long getSgstPaise() { return sgstPaise; }
    public long getIgstPaise() { return igstPaise; }
    public long getTotalPaise() { return totalPaise; }
    public InvoiceStatus getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
