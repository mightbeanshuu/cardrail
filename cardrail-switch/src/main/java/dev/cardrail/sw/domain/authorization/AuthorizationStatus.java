package dev.cardrail.sw.domain.authorization;

public enum AuthorizationStatus {
    /** Transmission key claimed, decision not yet recorded. Never visible as a final answer. */
    PENDING,
    AUTHORIZED,
    DECLINED,
    REVERSED,
    CAPTURED,
    EXPIRED
}
