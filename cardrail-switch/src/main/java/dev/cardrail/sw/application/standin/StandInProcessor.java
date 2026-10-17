package dev.cardrail.sw.application.standin;

import dev.cardrail.sw.application.SwitchMetrics;
import dev.cardrail.sw.application.issuer.IssuerOutcome;
import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.standin.StandInDecision;
import dev.cardrail.sw.domain.standin.StandInPolicy;
import dev.cardrail.sw.port.StandInLimits;
import dev.cardrail.sw.port.TokenizedCard;
import org.springframework.stereotype.Component;

@Component
public class StandInProcessor {

    private final StandInLimits limits;
    private final SwitchMetrics metrics;

    public StandInProcessor(StandInLimits limits, SwitchMetrics metrics) {
        this.limits = limits;
        this.metrics = metrics;
    }

    public StandInDecision decide(AuthorizationRequest request, TokenizedCard card, RiskOutcome risk,
                                  IssuerOutcome.Trigger trigger) {
        StandInDecision decision = StandInPolicy.decide(request.amountMinor(),
                limits.limitFor(card.bin(), request.currency()), risk);
        metrics.standInDecision(decision.approved(), trigger.tag());
        return decision;
    }
}
