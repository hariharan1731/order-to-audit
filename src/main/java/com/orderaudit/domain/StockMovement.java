package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * One row of the stock ledger. @Immutable tells Hibernate never to UPDATE it. The database trigger
 * is the second, stronger lock: even raw SQL cannot change or delete a row.
 * There are no setters: a movement can only be created.
 */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "qty_signed", nullable = false)
    private long qtySigned;

    /** Paise per base unit, 4 decimals. NULL means unvalued. */
    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false)
    private MovementType movementType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "reverses_id")
    private Long reversesId;

    /** Filled by the database (DEFAULT now()). */
    @Column(name = "posted_at", insertable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "posted_by", nullable = false)
    private String postedBy;

    protected StockMovement() {
    }

    public StockMovement(Long itemId, MovementType movementType, long qtySigned, BigDecimal unitCost,
                         SourceType sourceType, Long sourceId, Long reversesId, String postedBy) {
        this.itemId = itemId;
        this.movementType = movementType;
        this.qtySigned = qtySigned;
        this.unitCost = unitCost;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.reversesId = reversesId;
        this.postedBy = postedBy;
    }

    public Long getId() { return id; }
    public Long getItemId() { return itemId; }
    public long getQtySigned() { return qtySigned; }
    public BigDecimal getUnitCost() { return unitCost; }
    public MovementType getMovementType() { return movementType; }
    public SourceType getSourceType() { return sourceType; }
    public Long getSourceId() { return sourceId; }
    public Long getReversesId() { return reversesId; }
    public Instant getPostedAt() { return postedAt; }
    public String getPostedBy() { return postedBy; }
}
