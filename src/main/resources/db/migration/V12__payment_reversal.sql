-- V12: reversing a payment (bounced cheque, wrong customer, keyed twice).
-- The payment and its allocations are NEVER edited or deleted. A reversal is a new row saying
-- "this payment no longer counts"; outstanding amounts ignore reversed payments.
-- Append-only, enforced by the database itself (same idea as audit_log and credit_note).
CREATE TABLE payment_reversal (
    id            BIGSERIAL PRIMARY KEY,
    payment_id    BIGINT       NOT NULL REFERENCES payment (id),
    reversal_date DATE         NOT NULL,
    reason        VARCHAR(200) NOT NULL,
    reversed_by   VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_reversal_payment UNIQUE (payment_id),   -- a payment can only be reversed once
    CONSTRAINT ck_payment_reversal_reason CHECK (length(btrim(reason)) > 0)
);

CREATE FUNCTION forbid_payment_reversal_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment_reversal is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_reversal_no_update_delete
    BEFORE UPDATE OR DELETE ON payment_reversal
    FOR EACH ROW EXECUTE FUNCTION forbid_payment_reversal_change();

CREATE TRIGGER trg_payment_reversal_no_truncate
    BEFORE TRUNCATE ON payment_reversal
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_payment_reversal_change();
