-- V11: credit notes. A credit note fully reverses one invoice (goods came back).
-- The original invoice is never deleted: it is marked CANCELLED and the credit note cancels it out in the books.

CREATE TABLE credit_note_sequence (
    fy_label    VARCHAR(7) PRIMARY KEY,
    last_number INT NOT NULL
);

CREATE TABLE credit_note (
    id                 BIGSERIAL PRIMARY KEY,
    credit_note_number VARCHAR(30)  NOT NULL UNIQUE,
    fy_label           VARCHAR(7)   NOT NULL,
    invoice_id         BIGINT       NOT NULL UNIQUE REFERENCES invoice (id),   -- one full credit note per invoice
    credit_date        DATE         NOT NULL,
    taxable_paise      BIGINT       NOT NULL,
    cgst_paise         BIGINT       NOT NULL,
    sgst_paise         BIGINT       NOT NULL,
    igst_paise         BIGINT       NOT NULL,
    total_paise        BIGINT       NOT NULL,
    reason             VARCHAR(300) NOT NULL,
    created_by         VARCHAR(100) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_credit_total CHECK (total_paise = taxable_paise + cgst_paise + sgst_paise + igst_paise)
);

-- A credit note is a tax document: once issued it can never be edited or removed.
CREATE FUNCTION forbid_credit_note_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'credit_note is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_credit_note_no_update_delete
    BEFORE UPDATE OR DELETE ON credit_note
    FOR EACH ROW EXECUTE FUNCTION forbid_credit_note_change();
