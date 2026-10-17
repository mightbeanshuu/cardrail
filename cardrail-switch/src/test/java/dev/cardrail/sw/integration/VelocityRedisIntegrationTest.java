package dev.cardrail.sw.integration;

import dev.cardrail.sw.adapter.redis.RedisVelocityCounter;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.risk.VelocityLimits;
import dev.cardrail.sw.port.VelocityStoreUnavailableException;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CardrailIntegrationTest
class VelocityRedisIntegrationTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    @Autowired TestFixtures fixtures;
    @Autowired RedisVelocityCounter counter;
    @Autowired AuthorizeService authorize;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void countLimitIs65WithinTheWindowAndResetsAfterIt() {
        VelocityLimits limits = new VelocityLimits(3, 1_000_000);
        for (int i = 0; i < 3; i++) {
            assertThat(check("tok-a", 100, limits, T0.plusSeconds(i))).isEqualTo(RiskOutcome.PASS);
        }
        assertThat(check("tok-a", 100, limits, T0.plusSeconds(10)))
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.EXCEEDS_FREQUENCY_LIMIT, VelocityLimits.COUNT_RULE));
        assertThat(check("tok-a", 100, limits, T0.plus(WINDOW).plusSeconds(1)))
                .as("the first attempts have left the window").isEqualTo(RiskOutcome.PASS);
    }

    @Test
    void amountLimitIs61AndADeclinedAttemptDoesNotConsumeTheAllowance() {
        VelocityLimits limits = new VelocityLimits(100, 10_000);
        assertThat(check("tok-b", 8_000, limits, T0)).isEqualTo(RiskOutcome.PASS);
        assertThat(check("tok-b", 2_001, limits, T0.plusSeconds(1)))
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT, VelocityLimits.AMOUNT_RULE));
        assertThat(check("tok-b", 2_000, limits, T0.plusSeconds(2))).isEqualTo(RiskOutcome.PASS);
    }

    @Test
    void limitsHoldUnderConcurrencyBecauseTheScriptIsAtomic() {
        VelocityLimits limits = new VelocityLimits(25, 1_000_000);
        AtomicInteger passed = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                executor.submit(() -> {
                    if (check("tok-c", 1, limits, T0) == RiskOutcome.PASS) {
                        passed.incrementAndGet();
                    }
                });
            }
        }
        assertThat(passed).hasValue(25);
    }

    @Test
    void unreachableRedisIsReportedAsUnavailableQuickly() {
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        deadFactory.afterPropertiesSet();
        try {
            RedisVelocityCounter dead = new RedisVelocityCounter(new StringRedisTemplate(deadFactory));
            assertThatThrownBy(() -> dead.checkAndRecord("tok", 356, UUID.randomUUID(), 1, WINDOW,
                    new VelocityLimits(1, 1), T0))
                    .isInstanceOf(VelocityStoreUnavailableException.class);
        } finally {
            deadFactory.destroy();
        }
    }

    @Test
    void endToEndTheVelocityAmountLimitDeclinesWith61() {
        String pan = "5555555555554444";
        fixtures.openAccount(pan, 100_000_000);
        // Test profile: INR velocity limit is 5,00,000.00 per hour; per-transaction limit 2,00,000.00.
        for (int i = 0; i < 2; i++) {
            assertThat(authorize.authorize(RequestBuilder.purchase(pan, 20_000_000).build()).responseCode())
                    .isEqualTo(ResponseCode.APPROVED);
        }
        assertThat(authorize.authorize(RequestBuilder.purchase(pan, 10_000_001).build()).responseCode())
                .isEqualTo(ResponseCode.EXCEEDS_AMOUNT_LIMIT);
    }

    private RiskOutcome check(String token, long amount, VelocityLimits limits, Instant at) {
        return counter.checkAndRecord(token, 356, UUID.randomUUID(), amount, WINDOW, limits, at);
    }
}
