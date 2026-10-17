package dev.cardrail.sw.application.risk;

import dev.cardrail.sw.domain.risk.RiskOutcome;

public interface RiskRule {

    RiskOutcome evaluate(RiskContext context);
}
