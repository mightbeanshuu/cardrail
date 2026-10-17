package dev.cardrail.sw.config;

import dev.cardrail.sw.application.issuer.IssuerClient;
import dev.cardrail.sw.application.issuer.IssuerResilienceProperties;
import dev.cardrail.sw.port.IssuerGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds the issuer circuit breaker and time limiter from {@code cardrail.issuer.resilience.*}. They
 * are registered with the Resilience4j registries so their state and metrics show up in Micrometer.
 */
@Configuration(proxyBeanMethods = false)
class IssuerConfiguration {

    static final String ISSUER = "issuer";

    @Bean(destroyMethod = "close")
    ExecutorService issuerCallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    CircuitBreaker issuerCircuitBreaker(CircuitBreakerRegistry registry, IssuerResilienceProperties p) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.slidingWindowSize())
                .minimumNumberOfCalls(p.minimumNumberOfCalls())
                .failureRateThreshold(p.failureRateThreshold())
                .waitDurationInOpenState(p.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(p.permittedCallsInHalfOpen())
                .build();
        return registry.circuitBreaker(ISSUER, config);
    }

    @Bean
    TimeLimiter issuerTimeLimiter(TimeLimiterRegistry registry, IssuerResilienceProperties p) {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(p.timeout())
                .cancelRunningFuture(true)
                .build();
        return registry.timeLimiter(ISSUER, config);
    }

    @Bean
    IssuerClient issuerClient(IssuerGateway gateway, CircuitBreaker issuerCircuitBreaker,
                              TimeLimiter issuerTimeLimiter, ExecutorService issuerCallExecutor) {
        return new IssuerClient(gateway, issuerCircuitBreaker, issuerTimeLimiter, issuerCallExecutor);
    }
}
