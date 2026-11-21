package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A shared recipe: raw materials per batch (see RecipeLine) and the grams one batch yields. */
@Entity
@Table(name = "recipe")
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "bulk_yield_g", nullable = false)
    private long bulkYieldGrams;

    /** NULL means "use the global tolerance setting". */
    @Column(name = "tolerance_pct", precision = 5, scale = 2)
    private BigDecimal tolerancePct;

    @Column(nullable = false)
    private boolean active = true;

    protected Recipe() {
    }

    public Recipe(String name, long bulkYieldGrams, BigDecimal tolerancePct) {
        this.name = name;
        this.bulkYieldGrams = bulkYieldGrams;
        this.tolerancePct = tolerancePct;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public long getBulkYieldGrams() { return bulkYieldGrams; }
    public BigDecimal getTolerancePct() { return tolerancePct; }
    public boolean isActive() { return active; }
}
