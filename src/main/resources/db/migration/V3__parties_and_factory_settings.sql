-- V3: customers and suppliers ("parties"), plus the factory's state setting.
-- state_code is stored explicitly (GST code: Tamil Nadu 33, Karnataka 29). Place of supply compares codes,
-- never GSTIN text on its own. GSTIN is optional (unregistered buyers exist) but, if present, must be
-- well-formed and start with the same 2 digits as state_code. No checksum is checked, so fictional
-- demo GSTINs are allowed.
CREATE TABLE customer (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    gstin      VARCHAR(15),
    state_code VARCHAR(2)   NOT NULL,
    address    VARCHAR(300),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_state_code CHECK (state_code ~ '^[0-9]{2}$'),
    CONSTRAINT ck_customer_gstin CHECK (
        gstin IS NULL OR (
            gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'
            AND substring(gstin FROM 1 FOR 2) = state_code))
);
CREATE UNIQUE INDEX uq_customer_name_ci ON customer (lower(name));
CREATE UNIQUE INDEX uq_customer_gstin ON customer (gstin) WHERE gstin IS NOT NULL;

CREATE TABLE supplier (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    gstin      VARCHAR(15),
    state_code VARCHAR(2)   NOT NULL,
    address    VARCHAR(300),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_supplier_state_code CHECK (state_code ~ '^[0-9]{2}$'),
    CONSTRAINT ck_supplier_gstin CHECK (
        gstin IS NULL OR (
            gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'
            AND substring(gstin FROM 1 FOR 2) = state_code))
);
CREATE UNIQUE INDEX uq_supplier_name_ci ON supplier (lower(name));
CREATE UNIQUE INDEX uq_supplier_gstin ON supplier (gstin) WHERE gstin IS NOT NULL;

-- Official: the factory is in Tamil Nadu (GST state code 33).
INSERT INTO app_setting (setting_key, setting_value, description)
VALUES ('factory_state_code', '33', 'Factory GST state code (Tamil Nadu = 33); decides CGST+SGST versus IGST');

-- Not supplied: stays NULL until the real or a clearly fictional demo value is entered.
INSERT INTO app_setting (setting_key, setting_value, description)
VALUES ('factory_gstin', NULL, 'Factory GSTIN; NULL until supplied');
