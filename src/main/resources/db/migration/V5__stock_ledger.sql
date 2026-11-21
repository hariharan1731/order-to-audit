-- V5: the stock ledger.
--   stock_movement : append-only history. One signed row per stock change. THE source of truth.
--   stock_balance  : a cache of SUM(stock_movement.qty_signed) per item, for speed. Never below zero.
--   stock_adjustment: a header document for opening stock and manual corrections (so every movement
--                     has a source document).
-- Quantities are whole base units (g, ml, pieces, packets). unit_cost is PAISE per base unit, 4 decimals
-- (flour at Rs 45/kg = 4.5 paise per gram; a weighted average such as 4.3125 needs 4 decimals).

CREATE TABLE stock_adjustment (
    id        BIGSERIAL PRIMARY KEY,
    kind      VARCHAR(20)  NOT NULL,
    reason    VARCHAR(300) NOT NULL,
    posted_by VARCHAR(100) NOT NULL,
    posted_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_adjustment_kind CHECK (kind IN ('OPENING', 'ADJUSTMENT'))
);

CREATE TABLE stock_movement (
    id            BIGSERIAL PRIMARY KEY,
    item_id       BIGINT       NOT NULL REFERENCES item (id),
    qty_signed    BIGINT       NOT NULL,                 -- + stock in, - stock out
    unit_cost     NUMERIC(14,4),                         -- NULL = unvalued
    movement_type VARCHAR(20)  NOT NULL,
    source_type   VARCHAR(30)  NOT NULL,                 -- which kind of document caused it
    source_id     BIGINT       NOT NULL,                 -- which document
    reverses_id   BIGINT REFERENCES stock_movement (id), -- set only on REVERSAL rows
    posted_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    posted_by     VARCHAR(100) NOT NULL,
    CONSTRAINT ck_movement_qty_nonzero CHECK (qty_signed <> 0),
    CONSTRAINT ck_movement_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT ck_movement_type CHECK (movement_type IN
        ('OPENING', 'PURCHASE', 'PROD_ISSUE', 'PROD_OUTPUT', 'SALE', 'RETURN', 'ADJUST', 'REVERSAL')),
    CONSTRAINT ck_movement_reversal_link CHECK ((movement_type = 'REVERSAL') = (reverses_id IS NOT NULL)),
    CONSTRAINT uq_movement_reverses UNIQUE (reverses_id)  -- a movement can be reversed only once
);
CREATE INDEX ix_movement_item   ON stock_movement (item_id, id);
CREATE INDEX ix_movement_source ON stock_movement (source_type, source_id);

CREATE TABLE stock_balance (
    item_id  BIGINT PRIMARY KEY REFERENCES item (id),
    qty_base BIGINT NOT NULL,
    CONSTRAINT ck_balance_not_negative CHECK (qty_base >= 0)
);

-- Append-only, enforced by the database itself: no UPDATE, DELETE or TRUNCATE on the ledger, ever.
CREATE FUNCTION forbid_stock_movement_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'stock_movement is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movement_no_update_delete
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION forbid_stock_movement_change();

CREATE TRIGGER trg_stock_movement_no_truncate
    BEFORE TRUNCATE ON stock_movement
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_stock_movement_change();
