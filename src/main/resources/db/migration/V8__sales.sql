-- V8: the sales loop. Prices, orders, invoices.
-- Money is whole PAISE (BIGINT). Quantities are whole packets. Prices are paise per packet.

-- Price list: one default price per finished item; optional customer-specific override.
CREATE TABLE item_price (
    item_id     BIGINT PRIMARY KEY REFERENCES item (id),
    price_paise BIGINT NOT NULL,
    CONSTRAINT ck_item_price CHECK (price_paise >= 0)
);

CREATE TABLE customer_price (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customer (id),
    item_id     BIGINT NOT NULL REFERENCES item (id),
    price_paise BIGINT NOT NULL,
    CONSTRAINT ck_customer_price CHECK (price_paise >= 0),
    CONSTRAINT uq_customer_price UNIQUE (customer_id, item_id)
);

-- An order from a salesman. client_request_id lets a phone safely re-send the same order (no duplicates).
CREATE TABLE sales_order (
    id                BIGSERIAL PRIMARY KEY,
    customer_id       BIGINT       NOT NULL REFERENCES customer (id),
    salesman          VARCHAR(100) NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    client_request_id VARCHAR(60)  UNIQUE,
    note              VARCHAR(300),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_by        VARCHAR(100),
    decided_at        TIMESTAMPTZ,
    reject_reason     VARCHAR(300),
    dispatched_by     VARCHAR(100),
    dispatched_at     TIMESTAMPTZ,
    CONSTRAINT ck_order_status CHECK (status IN ('SUBMITTED', 'APPROVED', 'INVOICED', 'DISPATCHED', 'REJECTED'))
);
CREATE INDEX ix_order_status ON sales_order (status);
CREATE INDEX ix_order_salesman ON sales_order (salesman);

CREATE TABLE order_line (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES sales_order (id),
    item_id          BIGINT NOT NULL REFERENCES item (id),
    quantity         BIGINT NOT NULL,
    unit_price_paise BIGINT NOT NULL,
    CONSTRAINT ck_order_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_order_line_price CHECK (unit_price_paise >= 0),
    CONSTRAINT uq_order_line UNIQUE (order_id, item_id)
);

-- Gapless invoice numbers per financial year: one counter row per year, locked while a number is taken.
CREATE TABLE invoice_sequence (
    fy_label    VARCHAR(7) PRIMARY KEY,
    last_number INT NOT NULL
);

-- A tax invoice. Everything is a SNAPSHOT (names, GSTIN, rates) so later master-data edits never change it.
CREATE TABLE invoice (
    id                 BIGSERIAL PRIMARY KEY,
    invoice_number     VARCHAR(30)  NOT NULL UNIQUE,
    fy_label           VARCHAR(7)   NOT NULL,
    order_id           BIGINT       NOT NULL UNIQUE REFERENCES sales_order (id),
    customer_id        BIGINT       NOT NULL REFERENCES customer (id),
    customer_name      VARCHAR(150) NOT NULL,
    customer_gstin     VARCHAR(15),
    customer_state     VARCHAR(2)   NOT NULL,
    supply_state       VARCHAR(2)   NOT NULL,
    intra_state        BOOLEAN      NOT NULL,
    invoice_date       DATE         NOT NULL,
    taxable_paise      BIGINT       NOT NULL,
    cgst_paise         BIGINT       NOT NULL,
    sgst_paise         BIGINT       NOT NULL,
    igst_paise         BIGINT       NOT NULL,
    total_paise        BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',
    created_by         VARCHAR(100) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_invoice_status CHECK (status IN ('ISSUED', 'CANCELLED')),
    CONSTRAINT ck_invoice_total CHECK (total_paise = taxable_paise + cgst_paise + sgst_paise + igst_paise),
    CONSTRAINT ck_invoice_tax_kind CHECK ((intra_state AND igst_paise = 0) OR (NOT intra_state AND cgst_paise = 0 AND sgst_paise = 0))
);

CREATE TABLE invoice_line (
    id               BIGSERIAL PRIMARY KEY,
    invoice_id       BIGINT        NOT NULL REFERENCES invoice (id),
    item_id          BIGINT        NOT NULL REFERENCES item (id),
    item_name        VARCHAR(150)  NOT NULL,
    hsn_code         VARCHAR(8),
    quantity         BIGINT        NOT NULL,
    unit_price_paise BIGINT        NOT NULL,
    taxable_paise    BIGINT        NOT NULL,
    gst_rate_percent NUMERIC(5,2)  NOT NULL,
    cgst_paise       BIGINT        NOT NULL,
    sgst_paise       BIGINT        NOT NULL,
    igst_paise       BIGINT        NOT NULL
);
CREATE INDEX ix_invoice_line_invoice ON invoice_line (invoice_id);
