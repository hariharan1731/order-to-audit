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

/** Planned vs actual consumption of one item in one run, with its cost and variance (a snapshot). */
@Entity
@Table(name = "run_line")
public class RunLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlannedSource source;

    @Column(name = "planned_qty", nullable = false)
    private long plannedQty;

    @Column(name = "actual_qty", nullable = false)
    private long actualQty;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "line_cost_paise", nullable = false)
    private long lineCostPaise;

    @Column(name = "variance_pct", precision = 8, scale = 2)
    private BigDecimal variancePct;

    @Column(nullable = false)
    private boolean flagged;

    protected RunLine() {
    }

    public RunLine(Long runId, Long itemId, PlannedSource source, long plannedQty, long actualQty,
                   BigDecimal unitCost, long lineCostPaise, Variance variance) {
        this.runId = runId;
        this.itemId = itemId;
        this.source = source;
        this.plannedQty = plannedQty;
        this.actualQty = actualQty;
        this.unitCost = unitCost;
        this.lineCostPaise = lineCostPaise;
        this.variancePct = variance.percent();
        this.flagged = variance.flagged();
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getItemId() { return itemId; }
    public PlannedSource getSource() { return source; }
    public long getPlannedQty() { return plannedQty; }
    public long getActualQty() { return actualQty; }
    public BigDecimal getUnitCost() { return unitCost; }
    public long getLineCostPaise() { return lineCostPaise; }
    public BigDecimal getVariancePct() { return variancePct; }
    public boolean isFlagged() { return flagged; }
}
