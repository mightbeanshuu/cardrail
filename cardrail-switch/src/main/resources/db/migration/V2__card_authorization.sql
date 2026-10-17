-- "authorization" is a reserved word in PostgreSQL, hence card_authorization.
-- There is deliberately no PAN column: only the token and the masked PAN are stored.

CREATE TABLE card_authorization (
    id                    uuid PRIMARY KEY,
    acquirer_id           text        NOT NULL,
    stan                  char(6)     NOT NULL,
    transmission_datetime char(10)    NOT NULL CHECK (transmission_datetime ~ '^[0-9]{10}$'),
    terminal_id           text        NOT NULL,
    rrn                   text        NOT NULL,
    payload_hash          char(64)    NOT NULL,
    token                 text,
    masked_pan            text,
    amount_minor          bigint      NOT NULL,
    currency              smallint    NOT NULL,
    mcc                   char(4)     NOT NULL,
    merchant_id           text        NOT NULL,
    merchant_name         text        NOT NULL,
    merchant_country      char(3)     NOT NULL,
    pos_entry_mode        char(3)     NOT NULL,
    status                text        NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED',
                                                                 'REVERSED', 'CAPTURED', 'EXPIRED')),
    response_code         char(2),
    auth_code             char(6),
    stand_in              boolean     NOT NULL DEFAULT false,
    decline_reason        text,
    captured_amount_minor bigint,
    captured_at           timestamptz,
    reversed_at           timestamptz,
    expired_at            timestamptz,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,

    CONSTRAINT card_authorization_transmission_key
        UNIQUE (acquirer_id, stan, transmission_datetime, terminal_id),
    CONSTRAINT card_authorization_decided
        CHECK ((status = 'PENDING') = (response_code IS NULL)),
    CONSTRAINT card_authorization_auth_code_iff_approved
        CHECK ((response_code = '00') = (auth_code IS NOT NULL)),
    CONSTRAINT card_authorization_capture_fields
        CHECK ((status = 'CAPTURED') = (captured_amount_minor IS NOT NULL AND captured_at IS NOT NULL)),
    CONSTRAINT card_authorization_capture_within_amount
        CHECK (captured_amount_minor IS NULL OR (captured_amount_minor > 0 AND captured_amount_minor <= amount_minor))
);

CREATE INDEX card_authorization_expiry_idx ON card_authorization (created_at) WHERE status = 'AUTHORIZED';
CREATE INDEX card_authorization_velocity_idx ON card_authorization (token, currency, created_at) WHERE token IS NOT NULL;
CREATE INDEX card_authorization_pending_idx ON card_authorization (created_at) WHERE status = 'PENDING';
CREATE INDEX card_authorization_captured_idx ON card_authorization (captured_at) WHERE status = 'CAPTURED';
