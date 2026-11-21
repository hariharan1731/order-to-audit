package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "unit_price_paise", nullable = false)
    private long unitPricePaise;

    protected OrderLine() {
    }

    public OrderLine(Long orderId, Long itemId, long quantity, long unitPricePaise) {
        this.orderId = orderId;
        this.itemId = itemId;
        this.quantity = quantity;
        this.unitPricePaise = unitPricePaise;
    }

    /** Admin edit before approval. */
    public void change(long newQuantity, long newUnitPricePaise) {
        this.quantity = newQuantity;
        this.unitPricePaise = newUnitPricePaise;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getItemId() { return itemId; }
    public long getQuantity() { return quantity; }
    public long getUnitPricePaise() { return unitPricePaise; }
}
