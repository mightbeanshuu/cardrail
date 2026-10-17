-- Card vault (cardrail-vault's VaultStore). One row per tokenized PAN; no plaintext PAN is stored.
-- token and blind_index are both unique: the first arbitrates random-token collisions, the second
-- makes tokenization idempotent, so concurrent first sightings of one PAN agree on a single token.

CREATE TABLE vault_record (
    token           char(16)    NOT NULL CHECK (token ~ '^[0-9]{16}$'),
    blind_index     bytea       NOT NULL CHECK (octet_length(blind_index) = 32),
    wrapped_dek     bytea       NOT NULL,
    dek_key_version text        NOT NULL,
    iv              bytea       NOT NULL,
    ciphertext      bytea       NOT NULL,
    masked_pan      text        NOT NULL CHECK (masked_pan ~ '^[0-9]{6}\*+[0-9]{4}$'),
    created_at      timestamptz NOT NULL,

    CONSTRAINT vault_record_pkey PRIMARY KEY (token),
    CONSTRAINT vault_record_blind_index_key UNIQUE (blind_index)
);
