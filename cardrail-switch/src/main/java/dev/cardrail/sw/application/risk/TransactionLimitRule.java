package dev.cardrail.sw.application.risk;

import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import org.springframework.stereotype.Component;

@Component
public class TransactionLimitRule implements RiskRule {

    public static final String NAME = "transaction-limit";

    private final RiskProperties properties;

    public TransactionLimitRule(RiskProperties properties) {
        this.properties = properties;
    }

    @Override
    public RiskOutcome evaluate(RiskContext context) {
        RiskProperties.CurrencyLimits limits = properties.currencies().get(context.request().currency());
        if (context.request().amountMinor() > limits.maxTransactionMinor()) {
            return new RiskOutcome.Decline(ResponseCode.EXCEEDS_AMOUNT_LIMIT, NAME);
        }
        return RiskOutcome.PASS;
    }
}
