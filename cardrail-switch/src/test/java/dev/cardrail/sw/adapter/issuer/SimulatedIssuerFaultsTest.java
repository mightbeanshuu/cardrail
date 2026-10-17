package dev.cardrail.sw.adapter.issuer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedIssuerFaultsTest {

    private static final SimulatedIssuerFaults.Settings OUTAGE =
            new SimulatedIssuerFaults.Settings(Duration.ofSeconds(3), 1.0);

    private final MutableClock clock = new MutableClock();

    @Test
    void settingsChangedAtRuntimeRevertOnceResetAfterHasPassed() {
        SimulatedIssuerFaults faults = new SimulatedIssuerFaults(
                new SimulatedIssuerProperties(Duration.ZERO, 0, Duration.ofMinutes(2)), clock);

        faults.set(OUTAGE);
        assertThat(faults.resetsAt()).contains(clock.instant().plus(Duration.ofMinutes(2)));

        clock.advance(Duration.ofSeconds(119));
        assertThat(faults.current()).isEqualTo(OUTAGE);
        assertThat(faults.shouldFail()).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(faults.current()).isEqualTo(new SimulatedIssuerFaults.Settings(Duration.ZERO, 0));
        assertThat(faults.resetsAt()).isEmpty();
        assertThat(faults.shouldFail()).isFalse();
    }

    @Test
    void withoutResetAfterSettingsStayUntilReset() {
        SimulatedIssuerFaults faults = new SimulatedIssuerFaults(
                new SimulatedIssuerProperties(Duration.ZERO, 0, null), clock);

        faults.set(OUTAGE);
        clock.advance(Duration.ofDays(1));
        assertThat(faults.current()).isEqualTo(OUTAGE);
        assertThat(faults.resetsAt()).isEmpty();

        faults.reset();
        assertThat(faults.current().failureRate()).isZero();
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-18T09:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
