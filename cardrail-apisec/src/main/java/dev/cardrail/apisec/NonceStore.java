package dev.cardrail.apisec;

import java.time.Instant;

/**
 * Remembers accepted {@code (consumerKey, nonce)} pairs until their timestamp can no longer pass the
 * freshness check, so a captured request cannot be replayed inside the window.
 */
public interface NonceStore {

    enum Outcome {
        /** First sighting; the pair is now recorded. */
        RECORDED,
        /** The pair is already recorded and has not expired. */
        ALREADY_SEEN,
        /** The pair is new but the store has no room without dropping unexpired entries. */
        CAPACITY_EXHAUSTED
    }

    /**
     * Atomically records the pair if it is not already present. Must be linearizable: of any number of
     * concurrent calls with the same pair, at most one returns {@link Outcome#RECORDED}.
     *
     * @param expiresAt instant after which the entry may be forgotten
     * @param now       current time, from the verifier's clock
     */
    Outcome record(String consumerKey, String nonce, Instant expiresAt, Instant now);
}
