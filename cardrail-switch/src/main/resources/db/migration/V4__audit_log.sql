-- Hash-chained audit log. hash = SHA-256(prev_hash + canonical record), computed by the application
-- (AuditHasher) while holding a transaction-scoped advisory lock, so appends are serialized and the
-- chain cannot fork.

CREATE TABLE audit_log (
    seq         bigserial PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor       text        NOT NULL,
    action      text        NOT NULL,
    entity      text        NOT NULL,
    entity_id   text        NOT NULL,
    details     jsonb       NOT NULL,
    prev_hash   char(64)    NOT NULL,
    hash        char(64)    NOT NULL UNIQUE
);

-- Row triggers do not fire on TRUNCATE; in a real deployment the application role is not granted
-- TRUNCATE (or UPDATE/DELETE) on this table at all, and the trigger is the second line of defence.
CREATE FUNCTION reject_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (% of seq % rejected)', TG_OP, OLD.seq
        USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
