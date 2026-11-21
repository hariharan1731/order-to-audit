package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One packaging line of a pack config (a pouch per packet, a carton per carton). */
@Entity
@Table(name = "pack_line")
public class PackLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pack_config_id", nullable = false)
    private Long packConfigId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private long quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PackBasis basis;

    protected PackLine() {
    }

    public PackLine(Long packConfigId, Long itemId, long quantity, PackBasis basis) {
        this.packConfigId = packConfigId;
        this.itemId = itemId;
        this.quantity = quantity;
        this.basis = basis;
    }

    public Long getId() { return id; }
    public Long getPackConfigId() { return packConfigId; }
    public Long getItemId() { return itemId; }
    public long getQuantity() { return quantity; }
    public PackBasis getBasis() { return basis; }
}
