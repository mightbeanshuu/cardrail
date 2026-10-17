-- Stand-in limits by BIN prefix. The longest matching prefix wins; '' is the catch-all.
CREATE TABLE stand_in_limit (
    bin_prefix       varchar(8) NOT NULL CHECK (bin_prefix ~ '^[0-9]{0,8}$'),
    currency         smallint   NOT NULL CHECK (currency BETWEEN 1 AND 999),
    max_amount_minor bigint     NOT NULL CHECK (max_amount_minor >= 0),
    PRIMARY KEY (bin_prefix, currency)
);

CREATE TABLE mcc_rule (
    mcc      char(4) PRIMARY KEY CHECK (mcc ~ '^[0-9]{4}$'),
    category text    NOT NULL,
    blocked  boolean NOT NULL DEFAULT false
);
