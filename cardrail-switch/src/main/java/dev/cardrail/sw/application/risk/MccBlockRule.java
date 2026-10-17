package dev.cardrail.sw.application.risk;

import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.port.MccRules;
import org.springframework.stereotype.Component;

@Component
public class MccBlockRule implements RiskRule {

    public static final String NAME = "mcc-blocked";

    private final MccRules mccRules;

    public MccBlockRule(MccRules mccRules) {
        this.mccRules = mccRules;
    }

    @Override
    public RiskOutcome evaluate(RiskContext context) {
        if (mccRules.isBlocked(context.request().mcc())) {
            return new RiskOutcome.Decline(ResponseCode.NOT_PERMITTED_TO_CARDHOLDER, NAME);
        }
        return RiskOutcome.PASS;
    }
}
