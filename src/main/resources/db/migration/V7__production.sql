-- V7: production runs. One run = one batch made from one pack configuration.
-- Planned quantities come from the BOM and are SNAPSHOTTED in run_line, so editing a recipe later
-- never changes a past variance report. unit_cost / cost figures are paise (4 decimals for rates).
CREATE TABLE production_run (
    id                   BIGSERIAL PRIMARY KEY,
    pack_config_id       BIGINT        NOT NULL REFERENCES pack_config (id),
    batch_multiplier     NUMERIC(10,4) NOT NULL,
    run_date             DATE          NOT NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'POSTED',
    expected_packets     BIGINT        NOT NULL,
    actual_good_packets  BIGINT        NOT NULL,
    damaged_pouches      BIGINT        NOT NULL DEFAULT 0,
    tolerance_pct        NUMERIC(5,2)  NOT NULL,           -- the tolerance used for this run
    yield_variance_pct   NUMERIC(8,2),
    yield_flagged        BOOLEAN       NOT NULL DEFAULT FALSE,
    total_material_paise BIGINT        NOT NULL DEFAULT 0,
    total_overhead_paise BIGINT        NOT NULL DEFAULT 0,
    total_cost_paise     BIGINT        NOT NULL DEFAULT 0,
    cost_per_packet      NUMERIC(14,4),                    -- paise per good packet; NULL if no good packets
    posted_by            VARCHAR(100)  NOT NULL,
    posted_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_run_multiplier CHECK (batch_multiplier > 0),
    CONSTRAINT ck_run_status CHECK (status IN ('POSTED', 'REVERSED')),
    CONSTRAINT ck_run_actual CHECK (actual_good_packets >= 0 AND damaged_pouches >= 0)
);

CREATE TABLE run_line (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT        NOT NULL REFERENCES production_run (id),
    item_id         BIGINT        NOT NULL REFERENCES item (id),
    source          VARCHAR(10)   NOT NULL,                -- RECIPE or PACK
    planned_qty     BIGINT        NOT NULL,
    actual_qty      BIGINT        NOT NULL,
    unit_cost       NUMERIC(14,4) NOT NULL,                -- weighted average cost at issue
    line_cost_paise BIGINT        NOT NULL,
    variance_pct    NUMERIC(8,2),                          -- NULL when planned is 0 but actual is not
    flagged         BOOLEAN       NOT NULL,
    CONSTRAINT ck_run_line_source CHECK (source IN ('RECIPE', 'PACK')),
    CONSTRAINT ck_run_line_qty CHECK (planned_qty >= 0 AND actual_qty >= 0),
    CONSTRAINT uq_run_line UNIQUE (run_id, item_id)
);

CREATE TABLE run_overhead (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT       NOT NULL REFERENCES production_run (id),
    description  VARCHAR(100) NOT NULL,
    amount_paise BIGINT       NOT NULL,
    CONSTRAINT ck_run_overhead_amount CHECK (amount_paise >= 0)
);
