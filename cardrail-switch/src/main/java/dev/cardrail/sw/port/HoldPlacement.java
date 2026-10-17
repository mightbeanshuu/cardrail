package dev.cardrail.sw.port;

public enum HoldPlacement {
    PLACED,
    INSUFFICIENT_FUNDS,
    ACCOUNT_BLOCKED,
    NO_ACCOUNT,
    CURRENCY_MISMATCH,
    /** The switch gave up on this authorization before the hold could be placed. */
    ALREADY_VOIDED
}
