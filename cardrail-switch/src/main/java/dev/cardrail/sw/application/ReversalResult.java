package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.authorization.AuthorizationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Repeating a reversal returns an equal result: same outcome, same released amount, same timestamp.
 *
 * @param releasedMinor amount moved back from held to available (0 for stand-in approvals, which hold nothing)
 */
public record ReversalResult(UUID authorizationId, Outcome outcome, AuthorizationStatus status, long releasedMinor,
                             Instant reversedAt) {

    public enum Outcome {
        REVERSED,
        /** The original was declined or its hold already expired; there was nothing to release. */
        NOTHING_TO_REVERSE
    }
}
