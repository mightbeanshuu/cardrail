package dev.cardrail.sw.adapter.issuer;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-adjustable fault injection, so stand-in processing can be demonstrated and tested. */
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

    private final Settings initial;
    private final AtomicReference<Settings> current;

    public SimulatedIssuerFaults(SimulatedIssuerProperties properties) {
        this.initial = new Settings(properties.latency(), properties.failureRate());
        this.current = new AtomicReference<>(initial);
    }

    public Settings current() {
        return current.get();
    }

    public void set(Settings settings) {
        current.set(settings);
    }

    public void reset() {
        current.set(initial);
    }

    boolean shouldFail() {
        double rate = current.get().failureRate();
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }
}
