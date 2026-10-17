package dev.cardrail.sw.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param expiryDays     AUTHORIZED holds older than this are released
 * @param abandonedAfter PENDING authorizations older than this are treated as abandoned and declined 96
 */
@ConfigurationProperties("cardrail.holds")
public record HoldProperties(int expiryDays, int batchSize, Duration abandonedAfter) {

    public HoldProperties {
        if (expiryDays < 1 || batchSize < 1) {
            throw new IllegalArgumentException("cardrail.holds.expiry-days and batch-size must be positive");
        }
    }
}
