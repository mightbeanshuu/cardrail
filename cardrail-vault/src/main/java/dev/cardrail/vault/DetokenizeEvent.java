package dev.cardrail.vault;

import java.time.Instant;

/**
 * Audit record of one {@link TokenVault#detokenize} call. Carries no PAN, masked or otherwise.
 *
 * @param token  the token asked for, or null if the input was not a well-formed token (it may have
 *               been a PAN passed by mistake, so it is not recorded)
 * @param kekId  KEK wrapping the record's DEK, or null if no record was found
 */
public record DetokenizeEvent(String token, DetokenizeReason reason, Instant at, String kekId, Outcome outcome) {

    public enum Outcome {
        GRANTED,
        MALFORMED_TOKEN,
        UNKNOWN_TOKEN,
        INTEGRITY_FAILURE,
        KEY_UNAVAILABLE
    }
}
