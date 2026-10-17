package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class SwitchMetrics {

    private final MeterRegistry registry;

    public SwitchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startAuthorization() {
        return Timer.start(registry);
    }

    public void authorizationCompleted(Timer.Sample sample, AuthorizationResult result) {
        sample.stop(Timer.builder("cardrail.authorization.latency")
                .description("Time from request to response, by response code")
                .tag("response_code", result.responseCode().code())
                .tag("stand_in", Boolean.toString(result.standIn()))
                .publishPercentileHistogram()
                .register(registry));
    }

    public void duplicate(String outcome) {
        Counter.builder("cardrail.authorization.duplicates")
                .description("Retransmissions of an already used transmission key")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void riskDecline(String rule) {
        Counter.builder("cardrail.risk.declines")
                .tag("rule", rule)
                .register(registry)
                .increment();
    }

    public void velocityDegraded() {
        Counter.builder("cardrail.risk.velocity.degraded")
                .description("Velocity checks answered from the database because Redis was unavailable")
                .register(registry)
                .increment();
    }

    public void standInDecision(boolean approved, String trigger) {
        Counter.builder("cardrail.stip.decisions")
                .tag("outcome", approved ? "approved" : "declined")
                .tag("trigger", trigger)
                .register(registry)
                .increment();
    }

    public void lateIssuerHoldReleased() {
        Counter.builder("cardrail.issuer.late_holds_released")
                .description("Holds placed by the issuer after the switch had stopped waiting")
                .register(registry)
                .increment();
    }

    public void outboxPublished(boolean success) {
        Counter.builder("cardrail.outbox.publish")
                .tag("result", success ? "success" : "failure")
                .register(registry)
                .increment();
    }
}
