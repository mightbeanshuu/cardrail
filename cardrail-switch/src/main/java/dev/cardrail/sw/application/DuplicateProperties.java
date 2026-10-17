package dev.cardrail.sw.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param payloadHashKey HMAC key for the payload fingerprint. Keyed because the fingerprint covers the
 *                       PAN, and an unkeyed hash of a PAN can be reversed by enumerating card numbers.
 * @param inFlightWait   how long a retransmission waits for the original to be decided before it is
 *                       answered 94 instead of replayed
 */
@ConfigurationProperties("cardrail.duplicates")
public record DuplicateProperties(String payloadHashKey, Duration inFlightWait, Duration pollInterval) {

    public DuplicateProperties {
        Objects.requireNonNull(payloadHashKey, "cardrail.duplicates.payload-hash-key");
        if (payloadHashKey.length() < 32) {
            throw new IllegalArgumentException("cardrail.duplicates.payload-hash-key must be at least 32 characters");
        }
        Objects.requireNonNull(inFlightWait, "cardrail.duplicates.in-flight-wait");
        Objects.requireNonNull(pollInterval, "cardrail.duplicates.poll-interval");
    }
}
