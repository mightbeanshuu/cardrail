package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.risk.VelocityLimits;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Shared rolling-window counter of authorization attempts per card and currency. */
public interface VelocityCounter {

    /**
     * Atomically discards attempts older than the window, checks this attempt against the limits and,
     * if it passes, records it.
     *
     * @throws VelocityStoreUnavailableException if the counter cannot be reached
     */
    RiskOutcome checkAndRecord(String token, int currency, UUID attemptId, long amountMinor, Duration window,
                               VelocityLimits limits, Instant now);
}
