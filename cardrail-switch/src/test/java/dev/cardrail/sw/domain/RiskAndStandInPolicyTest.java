package dev.cardrail.sw.domain;

import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.risk.VelocityLimits;
import dev.cardrail.sw.domain.standin.StandInDecision;
import dev.cardrail.sw.domain.standin.StandInPolicy;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class RiskAndStandInPolicyTest {

    private final VelocityLimits limits = new VelocityLimits(3, 10_000);

    @Test
    void velocityAllowsUpToTheCountLimit() {
        assertThat(limits.evaluate(2, 0, 100)).isEqualTo(RiskOutcome.PASS);
        assertThat(limits.evaluate(3, 0, 100))
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.EXCEEDS_FREQUENCY_LIMIT, VelocityLimits.COUNT_RULE));
    }

    @Test
    void velocityAllowsUpToTheAmountLimitInclusive() {
        assertThat(limits.evaluate(0, 9_000, 1_000)).isEqualTo(RiskOutcome.PASS);
        assertThat(limits.evaluate(0, 9_000, 1_001))
                .isEqualTo(new RiskOutcome.Decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT, VelocityLimits.AMOUNT_RULE));
    }

    @Test
    void degradedLimitsAreStricterButNeverZero() {
        assertThat(limits.scaledBy(0.5)).isEqualTo(new VelocityLimits(1, 5_000));
        assertThat(new VelocityLimits(1, 1).scaledBy(0.1)).isEqualTo(new VelocityLimits(1, 1));
    }

    @Test
    void standInApprovesUpToAndIncludingTheLimit() {
        assertThat(StandInPolicy.decide(50_000, OptionalLong.of(50_000), RiskOutcome.PASS).responseCode())
                .isEqualTo(ResponseCode.APPROVED);
    }

    @Test
    void standInDeclinesAboveTheLimitWith91() {
        StandInDecision decision = StandInPolicy.decide(50_001, OptionalLong.of(50_000), RiskOutcome.PASS);
        assertThat(decision.responseCode()).isEqualTo(ResponseCode.ISSUER_UNAVAILABLE);
        assertThat(decision.reason()).isEqualTo("over-stand-in-limit");
    }

    @Test
    void standInDeclinesWhenNoLimitIsConfigured() {
        assertThat(StandInPolicy.decide(1, OptionalLong.empty(), RiskOutcome.PASS).responseCode())
                .isEqualTo(ResponseCode.ISSUER_UNAVAILABLE);
    }

    @Test
    void standInNeverApprovesARequestThatFailedRisk() {
        RiskOutcome declined = new RiskOutcome.Decline(ResponseCode.NOT_PERMITTED_TO_CARDHOLDER, "mcc-blocked");
        assertThat(StandInPolicy.decide(1, OptionalLong.of(50_000), declined).responseCode())
                .isEqualTo(ResponseCode.ISSUER_UNAVAILABLE);
    }
}
