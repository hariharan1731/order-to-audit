package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An overhead cost (labour, gas, power...) charged to one run, in whole paise. */
@Entity
@Table(name = "run_overhead")
public class RunOverhead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(nullable = false)
    private String description;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    protected RunOverhead() {
    }

    public RunOverhead(Long runId, String description, long amountPaise) {
        this.runId = runId;
        this.description = description;
        this.amountPaise = amountPaise;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public String getDescription() { return description; }
    public long getAmountPaise() { return amountPaise; }
}
