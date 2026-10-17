-- Double-entry ledger. Balances are sums of signed journal entries; every journal sums to zero.

CREATE TABLE ledger_account (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_ref text        NOT NULL UNIQUE,
    kind        text        NOT NULL CHECK (kind IN ('CARDHOLDER_AVAILABLE', 'CARDHOLDER_HELD',
                                                     'SETTLEMENT_PAYABLE', 'ISSUER_FUNDING')),
    currency    smallint    NOT NULL CHECK (currency BETWEEN 1 AND 999),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE journal (
    id               uuid PRIMARY KEY,
    kind             text        NOT NULL CHECK (kind IN ('OPENING_BALANCE', 'HOLD', 'HOLD_RELEASE',
                                                          'HOLD_EXPIRY', 'CAPTURE')),
    authorization_id uuid,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX journal_authorization_idx ON journal (authorization_id) WHERE authorization_id IS NOT NULL;

CREATE TABLE journal_entry (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id        uuid   NOT NULL REFERENCES journal (id),
    ledger_account_id bigint NOT NULL REFERENCES ledger_account (id),
    amount_minor      bigint NOT NULL CHECK (amount_minor <> 0)
);

CREATE INDEX journal_entry_journal_idx ON journal_entry (journal_id);
CREATE INDEX journal_entry_account_idx ON journal_entry (ledger_account_id);

-- Entries are inserted one by one, so the invariant can only hold once the whole journal is written:
-- the check is a constraint trigger deferred to commit.
CREATE FUNCTION assert_journal_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    total numeric;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0) INTO total FROM journal_entry WHERE journal_id = NEW.journal_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal % is unbalanced: entries sum to %', NEW.journal_id, total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_balanced();

-- Posted ledger data is immutable; corrections are new journals. Without this, the balance check
-- above could be bypassed by updating an entry after commit.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER journal_append_only
    BEFORE UPDATE OR DELETE ON journal
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- The simulated issuer's view of each card account. available/held mirror the ledger balances of the
-- two linked ledger accounts and are what the row lock protects.
CREATE TABLE issuer_account (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token                       text        NOT NULL UNIQUE,
    currency                    smallint    NOT NULL CHECK (currency BETWEEN 1 AND 999),
    available_minor             bigint      NOT NULL CHECK (available_minor >= 0),
    held_minor                  bigint      NOT NULL DEFAULT 0 CHECK (held_minor >= 0),
    status                      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
    available_ledger_account_id bigint      NOT NULL UNIQUE REFERENCES ledger_account (id),
    held_ledger_account_id      bigint      NOT NULL UNIQUE REFERENCES ledger_account (id),
    version                     bigint      NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now()
);

-- One row per authorization the issuer was asked about. The primary key is also the arbiter between
-- a late issuer approval and the switch giving up on the issuer: whichever inserts first wins (see
-- JdbcIssuerLedger.voidHold).
CREATE TABLE issuer_hold (
    authorization_id  uuid PRIMARY KEY,
    issuer_account_id bigint REFERENCES issuer_account (id),
    amount_minor      bigint      NOT NULL CHECK (amount_minor >= 0),
    status            text        NOT NULL CHECK (status IN ('HELD', 'RELEASED', 'CAPTURED', 'VOIDED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT issuer_hold_voided_has_no_account CHECK ((status = 'VOIDED') = (issuer_account_id IS NULL))
);
