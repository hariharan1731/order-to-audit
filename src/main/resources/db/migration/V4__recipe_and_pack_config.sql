-- V4: the BOM, split in two so one recipe can serve several pack sizes.
--   recipe      = raw materials for one batch + how many grams the batch yields (bulk yield)
--   pack_config = one finished product: its pack size, packets per carton, and its packaging lines
-- All quantities are whole base units (grams, millilitres, pieces).

CREATE TABLE recipe (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    bulk_yield_g   BIGINT       NOT NULL,          -- grams of finished product one batch makes
    tolerance_pct  NUMERIC(5,2),                   -- NULL = use the global setting
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_recipe_yield CHECK (bulk_yield_g > 0),
    CONSTRAINT ck_recipe_tolerance CHECK (tolerance_pct IS NULL OR (tolerance_pct >= 0 AND tolerance_pct <= 100))
);
CREATE UNIQUE INDEX uq_recipe_name_ci ON recipe (lower(name));

CREATE TABLE recipe_line (
    id            BIGSERIAL PRIMARY KEY,
    recipe_id     BIGINT NOT NULL REFERENCES recipe (id),
    item_id       BIGINT NOT NULL REFERENCES item (id),
    quantity_base BIGINT NOT NULL,                 -- per ONE batch, in the item's base unit
    CONSTRAINT ck_recipe_line_qty CHECK (quantity_base > 0),
    CONSTRAINT uq_recipe_line UNIQUE (recipe_id, item_id)
);

CREATE TABLE pack_config (
    id                BIGSERIAL PRIMARY KEY,
    finished_item_id  BIGINT  NOT NULL UNIQUE REFERENCES item (id),  -- one config per finished product
    recipe_id         BIGINT  NOT NULL REFERENCES recipe (id),
    pack_size_g       INTEGER NOT NULL,
    packets_per_carton INTEGER NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pack_size CHECK (pack_size_g > 0),
    CONSTRAINT ck_packets_per_carton CHECK (packets_per_carton > 0)
);

CREATE TABLE pack_line (
    id             BIGSERIAL PRIMARY KEY,
    pack_config_id BIGINT      NOT NULL REFERENCES pack_config (id),
    item_id        BIGINT      NOT NULL REFERENCES item (id),
    quantity       BIGINT      NOT NULL,           -- pieces
    basis          VARCHAR(20) NOT NULL,           -- PER_PACKET (pouch) or PER_CARTON (carton)
    CONSTRAINT ck_pack_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_pack_line_basis CHECK (basis IN ('PER_PACKET', 'PER_CARTON')),
    CONSTRAINT uq_pack_line UNIQUE (pack_config_id, item_id)
);
