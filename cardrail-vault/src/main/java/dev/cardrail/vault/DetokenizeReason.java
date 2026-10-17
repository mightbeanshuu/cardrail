package dev.cardrail.vault;

/** Why a caller needs the full PAN back. Recorded in every {@link DetokenizeEvent}. */
public enum DetokenizeReason {
    /** Forwarding an authorization request to the issuer. */
    AUTHORIZATION,
    /** Forwarding a reversal to the issuer. */
    REVERSAL,
    /** Building clearing records for end-of-day settlement. */
    CLEARING,
    /** Chargeback or dispute handling. */
    DISPUTE
}
