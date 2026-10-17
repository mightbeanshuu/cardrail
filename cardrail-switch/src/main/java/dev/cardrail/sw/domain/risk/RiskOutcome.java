package dev.cardrail.sw.domain.risk;

import dev.cardrail.sw.domain.authorization.ResponseCode;

import java.util.Objects;

public sealed interface RiskOutcome {

    RiskOutcome PASS = new Pass();

    record Pass() implements RiskOutcome {
    }

    /** @param rule stable rule name, used in metrics and the stored decline reason */
    record Decline(ResponseCode code, String rule) implements RiskOutcome {
        public Decline {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(rule, "rule");
            if (code.isApproved()) {
                throw new IllegalArgumentException("a risk decline cannot carry an approval code");
            }
        }
    }
}
