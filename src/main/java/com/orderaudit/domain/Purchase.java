package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** A purchase document (header). Its lines are PurchaseLine rows. */
@Entity
@Table(name = "purchase")
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    private String reference;

    @Column(name = "posted_by", nullable = false)
    private String postedBy;

    @Column(name = "posted_at", insertable = false, updatable = false)
    private Instant postedAt;

    protected Purchase() {
    }

    public Purchase(Long supplierId, LocalDate purchaseDate, String reference, String postedBy) {
        this.supplierId = supplierId;
        this.purchaseDate = purchaseDate;
        this.reference = reference;
        this.postedBy = postedBy;
    }

    public Long getId() { return id; }
    public Long getSupplierId() { return supplierId; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public String getReference() { return reference; }
    public String getPostedBy() { return postedBy; }
    public Instant getPostedAt() { return postedAt; }
}
