-- V10: login accounts, roles, and the append-only audit log.

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,   -- never the password itself: a salted one-way hash
    role          VARCHAR(25)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_role CHECK (role IN ('SALESMAN', 'ADMIN', 'PRODUCTION_SUPERVISOR', 'ACCOUNTANT', 'OWNER'))
);
-- Usernames are compared ignoring case: "Admin" and "admin" are the same person.
CREATE UNIQUE INDEX uq_app_user_username ON app_user (lower(username));

-- Who did what, and when. Append-only, enforced by the database itself (same idea as stock_movement).
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor       VARCHAR(100) NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    entity_type VARCHAR(30)  NOT NULL,
    entity_id   BIGINT,
    details     VARCHAR(500)
);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_at ON audit_log (at);

CREATE FUNCTION forbid_audit_log_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_log_change();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_log_change();
