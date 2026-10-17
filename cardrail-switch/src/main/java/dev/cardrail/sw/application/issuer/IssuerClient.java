package dev.cardrail.sw.application.issuer;

import dev.cardrail.sw.port.IssuerDecision;
import dev.cardrail.sw.port.IssuerGateway;
import dev.cardrail.sw.port.IssuerRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

/**
 * Calls the issuer with a deadline (TimeLimiter) inside a circuit breaker. Timeouts count as
 * failures, so a slow issuer opens the circuit just like a failing one; while it is open the switch
 * stands in immediately instead of making every cardholder wait out the timeout.
 */
public class IssuerClient {

    private final IssuerGateway gateway;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;

    public IssuerClient(IssuerGateway gateway, CircuitBreaker circuitBreaker, TimeLimiter timeLimiter,
                        ExecutorService executor) {
        this.gateway = gateway;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
        this.executor = executor;
    }

    public IssuerOutcome authorize(IssuerRequest request) {
        try {
            IssuerDecision decision = circuitBreaker.executeCallable(() ->
                    timeLimiter.executeFutureSupplier(() -> executor.submit(() -> gateway.authorize(request))));
            return new IssuerOutcome.Answered(decision);
        } catch (CallNotPermittedException e) {
            return new IssuerOutcome.Unavailable(IssuerOutcome.Trigger.CIRCUIT_OPEN, "circuit breaker is open");
        } catch (TimeoutException e) {
            return new IssuerOutcome.Unavailable(IssuerOutcome.Trigger.TIMEOUT,
                    "no answer within " + timeLimiter.getTimeLimiterConfig().getTimeoutDuration());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new IssuerOutcome.Unavailable(IssuerOutcome.Trigger.ISSUER_ERROR, "interrupted");
        } catch (Exception e) {
            return new IssuerOutcome.Unavailable(IssuerOutcome.Trigger.ISSUER_ERROR, e.toString());
        }
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}
