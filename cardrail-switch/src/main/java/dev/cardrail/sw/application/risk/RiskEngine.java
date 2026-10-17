package dev.cardrail.sw.application.risk;

import dev.cardrail.sw.application.SwitchMetrics;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs the rules in order and stops at the first decline. Velocity runs last because it records the
 * attempt; a request already declined by a cheaper rule should not consume the card's allowance.
 */
@Component
public class RiskEngine {

    private final List<RiskRule> rules;
    private final SwitchMetrics metrics;

    public RiskEngine(TransactionLimitRule transactionLimit, MccBlockRule mccBlock, VelocityRule velocity,
                      SwitchMetrics metrics) {
        this.rules = List.of(transactionLimit, mccBlock, velocity);
        this.metrics = metrics;
    }

    public RiskOutcome evaluate(RiskContext context) {
        for (RiskRule rule : rules) {
            RiskOutcome outcome = rule.evaluate(context);
            if (outcome instanceof RiskOutcome.Decline decline) {
                metrics.riskDecline(decline.rule());
                return decline;
            }
        }
        return RiskOutcome.PASS;
    }
}
