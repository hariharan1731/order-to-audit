package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "purchase_line")
public class PurchaseLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_id", nullable = false)
    private Long purchaseId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "quantity_base", nullable = false)
    private long quantityBase;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    protected PurchaseLine() {
    }

    public PurchaseLine(Long purchaseId, Long itemId, long quantityBase, BigDecimal unitCost) {
        this.purchaseId = purchaseId;
        this.itemId = itemId;
        this.quantityBase = quantityBase;
        this.unitCost = unitCost;
    }

    public Long getId() { return id; }
    public Long getPurchaseId() { return purchaseId; }
    public Long getItemId() { return itemId; }
    public long getQuantityBase() { return quantityBase; }
    public BigDecimal getUnitCost() { return unitCost; }
}
