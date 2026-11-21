package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cached total for one item. The ledger is the truth; reconcile() proves this equals the ledger. */
@Entity
@Table(name = "stock_balance")
public class StockBalance {

    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "qty_base", nullable = false)
    private long qtyBase;

    protected StockBalance() {
    }

    public StockBalance(Long itemId, long qtyBase) {
        this.itemId = itemId;
        this.qtyBase = qtyBase;
    }

    public Long getItemId() { return itemId; }
    public long getQtyBase() { return qtyBase; }

    /** Add (or subtract) a quantity. Refuses to go below zero. */
    public void apply(long delta) {
        long updated = Math.addExact(qtyBase, delta);
        if (updated < 0) {
            throw new IllegalStateException("Balance would become negative");
        }
        this.qtyBase = updated;
    }
}
