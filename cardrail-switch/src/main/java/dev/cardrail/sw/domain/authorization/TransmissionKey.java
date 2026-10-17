package dev.cardrail.sw.domain.authorization;

import java.util.Objects;

/**
 * Identifies one transmission from an acquirer: (F32, F11, F7, F41). Two requests with the same key
 * are the same transmission, repeated. A 0400 reversal locates its original by the same elements.
 */
public record TransmissionKey(String acquirerId, String stan, String transmissionDateTime, String terminalId) {

    public TransmissionKey {
        Objects.requireNonNull(acquirerId, "acquirerId");
        Objects.requireNonNull(stan, "stan");
        Objects.requireNonNull(transmissionDateTime, "transmissionDateTime");
        Objects.requireNonNull(terminalId, "terminalId");
    }
}
