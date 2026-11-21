package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One finished product's packing setup: which recipe it uses, pack size, packets per carton. */
@Entity
@Table(name = "pack_config")
public class PackConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finished_item_id", nullable = false)
    private Long finishedItemId;

    @Column(name = "recipe_id", nullable = false)
    private Long recipeId;

    @Column(name = "pack_size_g", nullable = false)
    private int packSizeGrams;

    @Column(name = "packets_per_carton", nullable = false)
    private int packetsPerCarton;

    protected PackConfig() {
    }

    public PackConfig(Long finishedItemId, Long recipeId, int packSizeGrams, int packetsPerCarton) {
        this.finishedItemId = finishedItemId;
        this.recipeId = recipeId;
        this.packSizeGrams = packSizeGrams;
        this.packetsPerCarton = packetsPerCarton;
    }

    public Long getId() { return id; }
    public Long getFinishedItemId() { return finishedItemId; }
    public Long getRecipeId() { return recipeId; }
    public int getPackSizeGrams() { return packSizeGrams; }
    public int getPacketsPerCarton() { return packetsPerCarton; }
}
