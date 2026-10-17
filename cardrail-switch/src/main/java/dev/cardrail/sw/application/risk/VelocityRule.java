package dev.cardrail.sw.application.risk;

import dev.cardrail.sw.application.SwitchMetrics;
import dev.cardrail.sw.domain.risk.RiskOutcome;
import dev.cardrail.sw.domain.risk.VelocityLimits;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.AuthorizationRepository.RecentActivity;
import dev.cardrail.sw.port.VelocityCounter;
import dev.cardrail.sw.port.VelocityStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Attempt count and amount per card in a rolling window, counted in Redis so all switch instances
 * share one view. If Redis cannot be reached the rule does not fail open: it counts this card's
 * attempts in the authorization table instead and applies tighter limits (ADR 0005).
 */
@Component
public class VelocityRule implements RiskRule {

    private static final Logger log = LoggerFactory.getLogger(VelocityRule.class);

    private final VelocityCounter counter;
    private final AuthorizationRepository authorizations;
    private final RiskProperties properties;
    private final SwitchMetrics metrics;

    public VelocityRule(VelocityCounter counter, AuthorizationRepository authorizations, RiskProperties properties,
                        SwitchMetrics metrics) {
        this.counter = counter;
        this.authorizations = authorizations;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public RiskOutcome evaluate(RiskContext context) {
        RiskProperties.Velocity velocity = properties.velocity();
        VelocityLimits limits = new VelocityLimits(velocity.maxCount(),
                properties.currencies().get(context.request().currency()).velocityMaxAmountMinor());
        try {
            return counter.checkAndRecord(context.card().token(), context.request().currency(),
                    context.authorizationId(), context.request().amountMinor(), velocity.window(), limits,
                    context.now());
        } catch (VelocityStoreUnavailableException e) {
            log.warn("velocity store unavailable, applying degraded database limits: {}", e.getMessage());
            metrics.velocityDegraded();
            RecentActivity recent = authorizations.recentActivity(context.card().token(),
                    context.request().currency(), context.now().minus(velocity.window()), context.authorizationId());
            return limits.scaledBy(velocity.degradedRatio())
                    .evaluate(recent.count(), recent.amountMinor(), context.request().amountMinor());
        }
    }
}
