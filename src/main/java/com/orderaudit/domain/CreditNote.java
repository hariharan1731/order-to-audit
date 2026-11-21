package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/** A credit note: a snapshot of the amounts of the invoice it cancels. Never changes once issued. */
@Entity
@Immutable
@Table(name = "credit_note")
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credit_note_number", nullable = false)
    private String creditNoteNumber;

    @Column(name = "fy_label", nullable = false)
    private String fyLabel;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "credit_date", nullable = false)
    private LocalDate creditDate;

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

    @Column(nullable = false)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected CreditNote() {
    }

    public CreditNote(String creditNoteNumber, String fyLabel, Invoice invoice, LocalDate creditDate,
                      String reason, String createdBy) {
        this.creditNoteNumber = creditNoteNumber;
        this.fyLabel = fyLabel;
        this.invoiceId = invoice.getId();
        this.creditDate = creditDate;
        this.taxablePaise = invoice.getTaxablePaise();
        this.cgstPaise = invoice.getCgstPaise();
        this.sgstPaise = invoice.getSgstPaise();
        this.igstPaise = invoice.getIgstPaise();
        this.totalPaise = invoice.getTotalPaise();
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getCreditNoteNumber() { return creditNoteNumber; }
    public String getFyLabel() { return fyLabel; }
    public Long getInvoiceId() { return invoiceId; }
    public LocalDate getCreditDate() { return creditDate; }
    public long getTaxablePaise() { return taxablePaise; }
    public long getCgstPaise() { return cgstPaise; }
    public long getSgstPaise() { return sgstPaise; }
    public long getIgstPaise() { return igstPaise; }
    public long getTotalPaise() { return totalPaise; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
