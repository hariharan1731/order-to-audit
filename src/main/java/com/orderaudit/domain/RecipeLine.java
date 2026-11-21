package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One raw material of a recipe. Holds plain ids (no object links) to keep loading simple. */
@Entity
@Table(name = "recipe_line")
public class RecipeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipe_id", nullable = false)
    private Long recipeId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "quantity_base", nullable = false)
    private long quantityBase;

    protected RecipeLine() {
    }

    public RecipeLine(Long recipeId, Long itemId, long quantityBase) {
        this.recipeId = recipeId;
        this.itemId = itemId;
        this.quantityBase = quantityBase;
    }

    public Long getId() { return id; }
    public Long getRecipeId() { return recipeId; }
    public Long getItemId() { return itemId; }
    public long getQuantityBase() { return quantityBase; }
}
