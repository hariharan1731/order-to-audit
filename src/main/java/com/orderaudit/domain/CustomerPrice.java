package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A special price for one customer and one item. Wins over the default price. */
@Entity
@Table(name = "customer_price")
public class CustomerPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    protected CustomerPrice() {
    }

    public CustomerPrice(Long customerId, Long itemId, long pricePaise) {
        this.customerId = customerId;
        this.itemId = itemId;
        this.pricePaise = pricePaise;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public Long getItemId() { return itemId; }
    public long getPricePaise() { return pricePaise; }
    public void setPricePaise(long pricePaise) { this.pricePaise = pricePaise; }
}
