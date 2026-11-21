-- V6: purchases (raw material and packaging received from a supplier).
-- A purchase is a document. Posting it appends PURCHASE rows to the stock ledger (source = this purchase).
-- unit_cost is paise per base unit, 4 decimals (Rs 45/kg = 4.5 paise per gram).
CREATE TABLE purchase (
    id            BIGSERIAL PRIMARY KEY,
    supplier_id   BIGINT       NOT NULL REFERENCES supplier (id),
    purchase_date DATE         NOT NULL,
    reference     VARCHAR(60),                       -- supplier's invoice number, optional
    posted_by     VARCHAR(100) NOT NULL,
    posted_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE purchase_line (
    id            BIGSERIAL PRIMARY KEY,
    purchase_id   BIGINT        NOT NULL REFERENCES purchase (id),
    item_id       BIGINT        NOT NULL REFERENCES item (id),
    quantity_base BIGINT        NOT NULL,
    unit_cost     NUMERIC(14,4) NOT NULL,
    CONSTRAINT ck_purchase_line_qty  CHECK (quantity_base > 0),
    CONSTRAINT ck_purchase_line_cost CHECK (unit_cost >= 0),
    CONSTRAINT uq_purchase_line UNIQUE (purchase_id, item_id)
);
