package dev.cardrail.sw.application.issuer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param timeout                   how long to wait for the issuer before standing in
 * @param slidingWindowSize         number of recent calls the failure rate is computed over
 * @param minimumNumberOfCalls      calls needed in the window before the circuit may open
 * @param failureRateThreshold      percentage of failed calls that opens the circuit
 * @param waitInOpenState           how long the circuit stays open before probing the issuer again
 * @param permittedCallsInHalfOpen  probe calls allowed while half open
 */
@ConfigurationProperties("cardrail.issuer.resilience")
public record IssuerResilienceProperties(
        Duration timeout,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        Duration waitInOpenState,
        int permittedCallsInHalfOpen) {
}
