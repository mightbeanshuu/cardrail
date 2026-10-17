package dev.cardrail.clearing.application;

public enum EventOutcome {
    RECORDED,
    /** Processed and remembered, but produced no clearing record. */
    IGNORED,
    /** Already processed; nothing was written. */
    DUPLICATE
}
