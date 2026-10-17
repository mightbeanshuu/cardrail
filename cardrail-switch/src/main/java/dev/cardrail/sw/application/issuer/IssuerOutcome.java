package dev.cardrail.sw.application.issuer;

import dev.cardrail.sw.port.IssuerDecision;

import java.util.Locale;

public sealed interface IssuerOutcome {

    record Answered(IssuerDecision decision) implements IssuerOutcome {
    }

    record Unavailable(Trigger trigger, String detail) implements IssuerOutcome {
    }

    /** Why the switch has to stand in. */
    enum Trigger {
        TIMEOUT,
        CIRCUIT_OPEN,
        ISSUER_ERROR;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
