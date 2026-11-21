-- V1: first migration. Never edit this file after it has run; add V2, V3, ... instead.
-- app_setting holds configurable values (key/value). Each row is one setting.
CREATE TABLE app_setting (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(500),            -- NULL means "not set yet"
    description   VARCHAR(500) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Variance tolerance default (percent). The problem statement says "for example 3%".
INSERT INTO app_setting (setting_key, setting_value, description)
VALUES ('variance_tolerance_pct', '3', 'Flag a variance only if its size is strictly greater than this percent');

-- Opening finished-goods rate is deliberately NULL: not supplied, so stock stays "unvalued".
INSERT INTO app_setting (setting_key, setting_value, description)
VALUES ('opening_fg_rate_paise_per_packet', NULL, 'Opening finished-goods cost per packet in paise; NULL = unvalued');
