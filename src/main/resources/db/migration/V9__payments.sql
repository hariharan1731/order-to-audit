-- V9: payments received from customers, allocated to invoices.
-- A payment is split across one or more invoices of the SAME customer (checked by the service).
-- Outstanding of an invoice = invoice total - sum of its allocations.
CREATE TABLE payment (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT       NOT NULL REFERENCES customer (id),
    payment_date DATE         NOT NULL,
    amount_paise BIGINT       NOT NULL,
    method       VARCHAR(10)  NOT NULL,
    reference    VARCHAR(60),
    received_by  VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_payment_method CHECK (method IN ('CASH', 'UPI', 'BANK', 'CHEQUE'))
);
CREATE INDEX ix_payment_customer ON payment (customer_id);

CREATE TABLE payment_allocation (
    id           BIGSERIAL PRIMARY KEY,
    payment_id   BIGINT NOT NULL REFERENCES payment (id),
    invoice_id   BIGINT NOT NULL REFERENCES invoice (id),
    amount_paise BIGINT NOT NULL,
    CONSTRAINT ck_allocation_amount CHECK (amount_paise > 0),
    CONSTRAINT uq_allocation UNIQUE (payment_id, invoice_id)
);
CREATE INDEX ix_allocation_invoice ON payment_allocation (invoice_id);
