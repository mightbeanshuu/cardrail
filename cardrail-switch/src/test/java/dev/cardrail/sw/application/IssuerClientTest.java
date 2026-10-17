package dev.cardrail.sw.application;

import dev.cardrail.sw.application.issuer.IssuerClient;
import dev.cardrail.sw.application.issuer.IssuerOutcome;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.port.IssuerDecision;
import dev.cardrail.sw.port.IssuerGateway;
import dev.cardrail.sw.port.IssuerRequest;
import dev.cardrail.sw.port.IssuerUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IssuerClientTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("issuer", CircuitBreakerConfig.custom()
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .build());
    private final TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(100))
            .cancelRunningFuture(true)
            .build());
    private final AtomicInteger calls = new AtomicInteger();
    private final IssuerRequest request = new IssuerRequest(UUID.randomUUID(), "tok", 100, 356, "5411", "M");

    @AfterEach
    void shutdown() {
        executor.close();
    }

    @Test
    void passesTheIssuersAnswerThroughIncludingDeclines() {
        IssuerOutcome outcome = client(r -> IssuerDecision.declined(ResponseCode.INSUFFICIENT_FUNDS)).authorize(request);

        assertThat(outcome).isEqualTo(new IssuerOutcome.Answered(IssuerDecision.declined(ResponseCode.INSUFFICIENT_FUNDS)));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void slowIssuerTimesOut() {
        IssuerOutcome outcome = client(r -> {
            sleep(Duration.ofSeconds(2));
            return IssuerDecision.approved("123456");
        }).authorize(request);

        assertThat(outcome).isInstanceOfSatisfying(IssuerOutcome.Unavailable.class,
                u -> assertThat(u.trigger()).isEqualTo(IssuerOutcome.Trigger.TIMEOUT));
    }

    @Test
    void failuresOpenTheCircuitAndThenTheIssuerIsNotCalledAtAll() {
        IssuerClient client = client(r -> {
            throw new IssuerUnavailableException("down");
        });
        for (int i = 0; i < 4; i++) {
            assertThat(client.authorize(request)).isInstanceOfSatisfying(IssuerOutcome.Unavailable.class,
                    u -> assertThat(u.trigger()).isEqualTo(IssuerOutcome.Trigger.ISSUER_ERROR));
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        IssuerOutcome outcome = client.authorize(request);

        assertThat(outcome).isInstanceOfSatisfying(IssuerOutcome.Unavailable.class,
                u -> assertThat(u.trigger()).isEqualTo(IssuerOutcome.Trigger.CIRCUIT_OPEN));
        assertThat(calls).hasValue(4);
    }

    private IssuerClient client(IssuerGateway behaviour) {
        IssuerGateway counting = r -> {
            calls.incrementAndGet();
            return behaviour.authorize(r);
        };
        return new IssuerClient(counting, circuitBreaker, timeLimiter, executor);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IssuerUnavailableException("cancelled", e);
        }
    }
}
