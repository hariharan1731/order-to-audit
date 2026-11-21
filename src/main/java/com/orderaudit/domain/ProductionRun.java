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
import java.time.Instant;
import java.time.LocalDate;

/**
 * One production run (header). Planned figures and the tolerance are SNAPSHOTS taken when the run
 * was saved. Only the status may change later (POSTED -> REVERSED); everything else is history.
 */
@Entity
@Table(name = "production_run")
public class ProductionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pack_config_id", nullable = false)
    private Long packConfigId;

    @Column(name = "batch_multiplier", nullable = false, precision = 10, scale = 4)
    private BigDecimal batchMultiplier;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.POSTED;

    @Column(name = "expected_packets", nullable = false)
    private long expectedPackets;

    @Column(name = "actual_good_packets", nullable = false)
    private long actualGoodPackets;

    @Column(name = "damaged_pouches", nullable = false)
    private long damagedPouches;

    @Column(name = "tolerance_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tolerancePct;

    @Column(name = "yield_variance_pct", precision = 8, scale = 2)
    private BigDecimal yieldVariancePct;

    @Column(name = "yield_flagged", nullable = false)
    private boolean yieldFlagged;

    @Column(name = "total_material_paise", nullable = false)
    private long totalMaterialPaise;

    @Column(name = "total_overhead_paise", nullable = false)
    private long totalOverheadPaise;

    @Column(name = "total_cost_paise", nullable = false)
    private long totalCostPaise;

    @Column(name = "cost_per_packet", precision = 14, scale = 4)
    private BigDecimal costPerPacket;

    @Column(name = "posted_by", nullable = false)
    private String postedBy;

    @Column(name = "posted_at", insertable = false, updatable = false)
    private Instant postedAt;

    protected ProductionRun() {
    }

    public ProductionRun(Long packConfigId, BigDecimal batchMultiplier, LocalDate runDate,
                         long expectedPackets, long actualGoodPackets, long damagedPouches,
                         BigDecimal tolerancePct, Variance yieldVariance,
                         long totalMaterialPaise, long totalOverheadPaise, BigDecimal costPerPacket,
                         String postedBy) {
        this.packConfigId = packConfigId;
        this.batchMultiplier = batchMultiplier;
        this.runDate = runDate;
        this.expectedPackets = expectedPackets;
        this.actualGoodPackets = actualGoodPackets;
        this.damagedPouches = damagedPouches;
        this.tolerancePct = tolerancePct;
        this.yieldVariancePct = yieldVariance.percent();
        this.yieldFlagged = yieldVariance.flagged();
        this.totalMaterialPaise = totalMaterialPaise;
        this.totalOverheadPaise = totalOverheadPaise;
        this.totalCostPaise = totalMaterialPaise + totalOverheadPaise;
        this.costPerPacket = costPerPacket;
        this.postedBy = postedBy;
    }

    /** The only change allowed after posting. */
    public void markReversed() {
        this.status = RunStatus.REVERSED;
    }

    public Long getId() { return id; }
    public Long getPackConfigId() { return packConfigId; }
    public BigDecimal getBatchMultiplier() { return batchMultiplier; }
    public LocalDate getRunDate() { return runDate; }
    public RunStatus getStatus() { return status; }
    public long getExpectedPackets() { return expectedPackets; }
    public long getActualGoodPackets() { return actualGoodPackets; }
    public long getDamagedPouches() { return damagedPouches; }
    public BigDecimal getTolerancePct() { return tolerancePct; }
    public BigDecimal getYieldVariancePct() { return yieldVariancePct; }
    public boolean isYieldFlagged() { return yieldFlagged; }
    public long getTotalMaterialPaise() { return totalMaterialPaise; }
    public long getTotalOverheadPaise() { return totalOverheadPaise; }
    public long getTotalCostPaise() { return totalCostPaise; }
    public BigDecimal getCostPerPacket() { return costPerPacket; }
    public String getPostedBy() { return postedBy; }
    public Instant getPostedAt() { return postedAt; }
}
