package dev.cardrail.sw.application;

import dev.cardrail.sw.application.issuer.IssuerOutcome;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.standin.StandInDecision;
import dev.cardrail.sw.port.IssuerDecision;

import java.util.Objects;

/**
 * How an authorization was decided.
 *
 * @param reason         why it was declined (rule or policy name), or why stand-in approved
 * @param standInTrigger set exactly when {@code source} is {@link Source#STAND_IN}
 */
public record Decision(Source source, ResponseCode responseCode, String authCode, String reason,
                IssuerOutcome.Trigger standInTrigger) {

    public enum Source {
        RISK,
        ISSUER,
        STAND_IN,
        ABANDONED
    }

    public Decision {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(responseCode, "responseCode");
        if ((source == Source.STAND_IN) != (standInTrigger != null)) {
            throw new IllegalArgumentException("a stand-in trigger is present exactly for stand-in decisions");
        }
    }

    public boolean standIn() {
        return source == Source.STAND_IN;
    }

    /**
     * The issuer may have been asked without the switch hearing the answer; any hold it placed must
     * not survive this decision.
     */
    public boolean issuerAnswerUnknown() {
        return source == Source.STAND_IN || source == Source.ABANDONED;
    }

    public static Decision riskDeclined(RiskOutcome.Decline decline) {
        return new Decision(Source.RISK, decline.code(), null, decline.rule(), null);
    }

    public static Decision issuer(IssuerDecision decision) {
        return new Decision(Source.ISSUER, decision.responseCode(), decision.authCode(),
                decision.responseCode().isApproved() ? null : "issuer-declined", null);
    }

    public static Decision standIn(StandInDecision decision, String authCode, IssuerOutcome.Trigger trigger) {
        return new Decision(Source.STAND_IN, decision.responseCode(), decision.approved() ? authCode : null,
                decision.reason(), trigger);
    }

    public static Decision abandoned() {
        return new Decision(Source.ABANDONED, ResponseCode.SYSTEM_MALFUNCTION, null, "abandoned", null);
    }
}
