package dev.cardrail.sw.domain.risk;

import dev.cardrail.sw.domain.authorization.ResponseCode;

/**
 * Per-card limits over a rolling window: how many attempts and how much money. The Redis Lua script
 * (resources/redis/velocity.lua) applies the same rule atomically; this class is the reference used
 * by the database fallback and by tests.
 */
public record VelocityLimits(int maxCount, long maxAmountMinor) {

    public static final String COUNT_RULE = "velocity-count";
    public static final String AMOUNT_RULE = "velocity-amount";

    public VelocityLimits {
        if (maxCount < 1 || maxAmountMinor < 1) {
            throw new IllegalArgumentException("velocity limits must be positive");
        }
    }

    public RiskOutcome evaluate(long priorCount, long priorAmountMinor, long amountMinor) {
        if (priorCount + 1 > maxCount) {
            return new RiskOutcome.Decline(ResponseCode.EXCEEDS_FREQUENCY_LIMIT, COUNT_RULE);
        }
        if (priorAmountMinor + amountMinor > maxAmountMinor) {
            return new RiskOutcome.Decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT, AMOUNT_RULE);
        }
        return RiskOutcome.PASS;
    }

    /** Tighter limits for when the shared counter is unavailable and only local data can be used. */
    public VelocityLimits scaledBy(double ratio) {
        if (ratio <= 0 || ratio > 1) {
            throw new IllegalArgumentException("ratio must be in (0, 1]");
        }
        return new VelocityLimits(
                Math.max(1, (int) Math.floor(maxCount * ratio)),
                Math.max(1, (long) Math.floor(maxAmountMinor * ratio)));
    }
}
