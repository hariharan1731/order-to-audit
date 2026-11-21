package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Default selling price of an item in paise per packet. The id IS the item id (one price per item). */
@Entity
@Table(name = "item_price")
public class ItemPrice {

    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    protected ItemPrice() {
    }

    public ItemPrice(Long itemId, long pricePaise) {
        this.itemId = itemId;
        this.pricePaise = pricePaise;
    }

    public Long getItemId() { return itemId; }
    public long getPricePaise() { return pricePaise; }
    public void setPricePaise(long pricePaise) { this.pricePaise = pricePaise; }
}
