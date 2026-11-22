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

/** One row of the item table. An entity is a Java class that Hibernate maps to a table. */
@Entity
@Table(name = "item")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // the database assigns the id (BIGSERIAL)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING) // stored as text like 'RAW', not as a fragile number
    @Column(name = "item_type", nullable = false)
    private ItemType itemType;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_unit", nullable = false)
    private Unit baseUnit;

    @Column(name = "hsn_code")
    private String hsnCode;

    @Column(name = "gst_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstRatePercent;

    @Column(nullable = false)
    private boolean active = true;

    /** Hibernate needs an empty constructor; protected so normal code must use the real one. */
    protected Item() {
    }

    public Item(String name, ItemType itemType, Unit baseUnit, String hsnCode, BigDecimal gstRatePercent) {
        this.name = name;
        this.itemType = itemType;
        this.baseUnit = baseUnit;
        this.hsnCode = hsnCode;
        this.gstRatePercent = gstRatePercent;
    }

    /** Name, type and base unit never change (stock history depends on them); tax details may. */
    public void changeTaxDetails(String hsnCode, BigDecimal gstRatePercent) {
        this.hsnCode = hsnCode;
        this.gstRatePercent = gstRatePercent;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public ItemType getItemType() { return itemType; }
    public Unit getBaseUnit() { return baseUnit; }
    public String getHsnCode() { return hsnCode; }
    public BigDecimal getGstRatePercent() { return gstRatePercent; }
    public boolean isActive() { return active; }
}
