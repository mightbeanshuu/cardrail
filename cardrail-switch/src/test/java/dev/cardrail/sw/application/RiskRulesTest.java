package dev.cardrail.sw.application;

import dev.cardrail.sw.application.risk.MccBlockRule;
import dev.cardrail.sw.application.risk.RiskContext;
import dev.cardrail.sw.application.risk.RiskEngine;
import dev.cardrail.sw.application.risk.RiskProperties;
import dev.cardrail.sw.application.risk.TransactionLimitRule;
import dev.cardrail.sw.application.risk.VelocityRule;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.risk.VelocityLimits;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.AuthorizationRepository.RecentActivity;
import dev.cardrail.sw.port.TokenizedCard;
import dev.cardrail.sw.port.VelocityCounter;
import dev.cardrail.sw.port.VelocityStoreUnavailableException;
import dev.cardrail.sw.support.RequestBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final TokenizedCard CARD = new TokenizedCard("tok", "555555******4444", "555555", "4444");
    private static final RiskProperties PROPERTIES = new RiskProperties(
            Map.of(356, new RiskProperties.CurrencyLimits(100_000, 300_000)),
            new RiskProperties.Velocity(Duration.ofHours(1), 4, 0.5));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SwitchMetrics metrics = new SwitchMetrics(registry);
    private final VelocityCounter counter = mock(VelocityCounter.class);
    private final AuthorizationRepository authorizations = mock(AuthorizationRepository.class);

    @Test
    void transactionLimitIsInclusive() {
        TransactionLimitRule rule = new TransactionLimitRule(PROPERTIES);
        assertThat(rule.evaluate(context(100_000))).isEqualTo(RiskOutcome.PASS);
        assertThat(rule.evaluate(context(100_001)))
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT, TransactionLimitRule.NAME));
    }

    @Test
    void blockedMccIs57() {
        MccBlockRule rule = new MccBlockRule(mcc -> mcc.equals("7995"));
        assertThat(rule.evaluate(context(100, "7995")))
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.NOT_PERMITTED_TO_CARDHOLDER, MccBlockRule.NAME));
        assertThat(rule.evaluate(context(100, "5411"))).isEqualTo(RiskOutcome.PASS);
    }

    @Test
    void velocityUsesTheSharedCounterWithTheCurrencyLimits() {
        when(counter.checkAndRecord(anyString(), anyInt(), any(), anyLong(), any(), any(), any()))
                .thenReturn(RiskOutcome.PASS);

        assertThat(velocityRule().evaluate(context(100))).isEqualTo(RiskOutcome.PASS);

        verify(counter).checkAndRecord(eq("tok"), eq(356), any(), eq(100L), eq(Duration.ofHours(1)),
                eq(new VelocityLimits(4, 300_000)), eq(NOW));
        verify(authorizations, never()).recentActivity(anyString(), anyInt(), any(), any());
    }

    @Test
    void whenRedisIsDownVelocityFallsBackToTheDatabaseWithHalvedLimits() {
        when(counter.checkAndRecord(anyString(), anyInt(), any(), anyLong(), any(), any(), any()))
                .thenThrow(new VelocityStoreUnavailableException("connection refused", null));
        when(authorizations.recentActivity(eq("tok"), eq(356), eq(NOW.minus(Duration.ofHours(1))), any()))
                .thenReturn(new RecentActivity(1, 0), new RecentActivity(2, 0), new RecentActivity(0, 100_000));

        VelocityRule rule = velocityRule();
        assertThat(rule.evaluate(context(100))).isEqualTo(RiskOutcome.PASS);
        assertThat(rule.evaluate(context(100))).as("degraded count limit is 2, not 4")
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.EXCEEDS_FREQUENCY_LIMIT, VelocityLimits.COUNT_RULE));
        assertThat(rule.evaluate(context(50_001))).as("degraded amount limit is 150,000, not 300,000")
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT, VelocityLimits.AMOUNT_RULE));
        assertThat(registry.counter("cardrail.risk.velocity.degraded").count()).isEqualTo(3);
    }

    @Test
    void engineStopsAtTheFirstDeclineSoVelocityIsNotConsumed() {
        RiskEngine engine = new RiskEngine(new TransactionLimitRule(PROPERTIES), new MccBlockRule(mcc -> false),
                velocityRule(), metrics);

        RiskOutcome outcome = engine.evaluate(context(100_001));

        assertThat(outcome).isInstanceOf(RiskOutcome.Decline.class);
        verify(counter, never()).checkAndRecord(anyString(), anyInt(), any(), anyLong(), any(), any(), any());
        assertThat(registry.counter("cardrail.risk.declines", "rule", TransactionLimitRule.NAME).count()).isOne();
    }

    private VelocityRule velocityRule() {
        return new VelocityRule(counter, authorizations, PROPERTIES, metrics);
    }

    private static RiskContext context(long amount) {
        return context(amount, "5411");
    }

    private static RiskContext context(long amount, String mcc) {
        return new RiskContext(UUID.randomUUID(), RequestBuilder.purchase("5555555555554444", amount).mcc(mcc).build(),
                CARD, NOW);
    }
}
