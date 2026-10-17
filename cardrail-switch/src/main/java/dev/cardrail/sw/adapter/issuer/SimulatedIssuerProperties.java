package dev.cardrail.sw.adapter.issuer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Initial fault settings of the simulated issuer; the dev and demo profiles can change them at runtime.
 *
 * @param latency     added to every call before the issuer looks at the account
 * @param failureRate fraction of calls, 0 to 1, that fail with an exception instead of answering
 * @param resetAfter  how long settings changed at runtime last before these come back; null for until reset
 */
@ConfigurationProperties("cardrail.issuer.sim")
public record SimulatedIssuerProperties(Duration latency, double failureRate, Duration resetAfter) {
}
