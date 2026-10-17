package dev.cardrail.sw.adapter.issuer;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-adjustable fault injection, so stand-in processing can be demonstrated and tested. With
 * {@code cardrail.issuer.sim.reset-after} set, settings changed at runtime revert to the configured
 * ones once that long has passed.
 */
@Component
public class SimulatedIssuerFaults {

    public record Settings(Duration latency, double failureRate) {
        public Settings {
            if (latency.isNegative()) {
                throw new IllegalArgumentException("latency must not be negative");
            }
            if (failureRate < 0 || failureRate > 1) {
                throw new IllegalArgumentException("failureRate must be between 0 and 1");
            }
        }
    }

    /** @param resetsAt null when these settings stay until reset */
    private record Active(Settings settings, Instant resetsAt) {
    }

    private final Active initial;
    private final Duration resetAfter;
    private final Clock clock;
    private final AtomicReference<Active> current;

    public SimulatedIssuerFaults(SimulatedIssuerProperties properties, Clock clock) {
        this.initial = new Active(new Settings(properties.latency(), properties.failureRate()), null);
        this.resetAfter = properties.resetAfter();
        this.clock = clock;
        this.current = new AtomicReference<>(initial);
    }

    public Settings current() {
        return active().settings();
    }

    /** When the current settings revert to the configured ones, if they will. */
    public Optional<Instant> resetsAt() {
        return Optional.ofNullable(active().resetsAt());
    }

    public void set(Settings settings) {
        current.set(new Active(settings, resetAfter == null ? null : clock.instant().plus(resetAfter)));
    }

    public void reset() {
        current.set(initial);
    }

    boolean shouldFail() {
        double rate = current().failureRate();
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    private Active active() {
        Active active = current.get();
        if (active.resetsAt() != null && !clock.instant().isBefore(active.resetsAt())) {
            current.compareAndSet(active, initial);
            return initial;
        }
        return active;
    }
}
