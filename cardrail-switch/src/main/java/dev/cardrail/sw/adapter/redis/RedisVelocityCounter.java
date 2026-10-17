package dev.cardrail.sw.adapter.redis;

import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.risk.VelocityLimits;
import dev.cardrail.sw.port.VelocityCounter;
import dev.cardrail.sw.port.VelocityStoreUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RedisVelocityCounter implements VelocityCounter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("redis/velocity.lua"), List.class);

    private final StringRedisTemplate redis;

    public RedisVelocityCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RiskOutcome checkAndRecord(String token, int currency, UUID attemptId, long amountMinor, Duration window,
                                      VelocityLimits limits, Instant now) {
        List<?> result;
        try {
            result = redis.execute(SCRIPT, List.of(key(token, currency)),
                    Long.toString(now.toEpochMilli()),
                    Long.toString(window.toMillis()),
                    Long.toString(amountMinor),
                    Integer.toString(limits.maxCount()),
                    Long.toString(limits.maxAmountMinor()),
                    attemptId + ":" + amountMinor);
        } catch (DataAccessException e) {
            throw new VelocityStoreUnavailableException("velocity script failed: " + e.getMessage(), e);
        }
        if (result == null || result.isEmpty()) {
            throw new VelocityStoreUnavailableException("velocity script returned no result", null);
        }
        long verdict = ((Number) result.getFirst()).longValue();
        if (verdict == 1) {
            return new RiskOutcome.Decline(ResponseCode.EXCEEDS_FREQUENCY_LIMIT, VelocityLimits.COUNT_RULE);
        }
        if (verdict == 2) {
            return new RiskOutcome.Decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT, VelocityLimits.AMOUNT_RULE);
        }
        return RiskOutcome.PASS;
    }

    static String key(String token, int currency) {
        return "cardrail:velocity:" + token + ":" + currency;
    }
}
