-- V2: the item master. One table holds raw materials, packaging and finished goods.
CREATE TABLE item (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(120) NOT NULL,
    item_type        VARCHAR(20)  NOT NULL,
    base_unit        VARCHAR(20)  NOT NULL,       -- stock is always stored in this unit
    hsn_code         VARCHAR(8),                  -- NULL until supplied (not invented)
    gst_rate_percent NUMERIC(5,2) NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_item_type CHECK (item_type IN ('RAW', 'PACKAGING', 'FINISHED')),
    CONSTRAINT ck_item_base_unit CHECK (base_unit IN ('GRAM', 'MILLILITRE', 'PIECE', 'PACKET')),
    CONSTRAINT ck_item_gst_rate CHECK (gst_rate_percent >= 0 AND gst_rate_percent <= 100)
);

-- "Flour" and "flour" are the same item, so uniqueness ignores case.
CREATE UNIQUE INDEX uq_item_name_ci ON item (lower(name));
