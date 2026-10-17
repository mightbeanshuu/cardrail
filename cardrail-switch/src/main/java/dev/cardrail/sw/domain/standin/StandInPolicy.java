package dev.cardrail.sw.domain.standin;

import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;

import java.util.OptionalLong;

/**
 * Stand-in processing: deciding on the issuer's behalf when the issuer cannot answer. The switch
 * carries the risk of every stand-in approval, so the policy only approves small amounts on cards
 * that already passed risk, and declines with 91 otherwise.
 */
public final class StandInPolicy {

    private StandInPolicy() {
    }

    public static StandInDecision decide(long amountMinor, OptionalLong standInLimitMinor, RiskOutcome risk) {
        if (!(risk instanceof RiskOutcome.Pass)) {
            return new StandInDecision(ResponseCode.ISSUER_UNAVAILABLE, "risk-not-passed");
        }
        if (standInLimitMinor.isEmpty()) {
            return new StandInDecision(ResponseCode.ISSUER_UNAVAILABLE, "no-stand-in-limit");
        }
        if (amountMinor > standInLimitMinor.getAsLong()) {
            return new StandInDecision(ResponseCode.ISSUER_UNAVAILABLE, "over-stand-in-limit");
        }
        return new StandInDecision(ResponseCode.APPROVED, "within-stand-in-limit");
    }
}
