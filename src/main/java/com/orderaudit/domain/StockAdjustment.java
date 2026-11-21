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

/** Header document for opening stock and manual corrections. Gives those movements a source document. */
@Entity
@Table(name = "stock_adjustment")
public class StockAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdjustmentKind kind;

    @Column(nullable = false)
    private String reason;

    @Column(name = "posted_by", nullable = false)
    private String postedBy;

    /** Filled by the database (DEFAULT now()). */
    @Column(name = "posted_at", insertable = false, updatable = false)
    private Instant postedAt;

    protected StockAdjustment() {
    }

    public StockAdjustment(AdjustmentKind kind, String reason, String postedBy) {
        this.kind = kind;
        this.reason = reason;
        this.postedBy = postedBy;
    }

    public Long getId() { return id; }
    public AdjustmentKind getKind() { return kind; }
    public String getReason() { return reason; }
    public String getPostedBy() { return postedBy; }
    public Instant getPostedAt() { return postedAt; }
}
