package dev.cardrail.sw.adapter.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClientRateLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void aClientGetsItsBurstThenWaitsForTheRefill() {
        ClientRateLimiter limiter = limiter(3, 60, 100);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("203.0.113.7")).isEmpty();
        }
        assertThat(limiter.tryAcquire("203.0.113.7")).contains(Duration.ofSeconds(1));

        clock.advance(Duration.ofMillis(400));
        assertThat(limiter.tryAcquire("203.0.113.7")).contains(Duration.ofMillis(600));
        clock.advance(Duration.ofMillis(600));
        assertThat(limiter.tryAcquire("203.0.113.7")).isEmpty();
    }

    @Test
    void clientsHaveSeparateBuckets() {
        ClientRateLimiter limiter = limiter(1, 1, 100);

        assertThat(limiter.tryAcquire("203.0.113.7")).isEmpty();
        assertThat(limiter.tryAcquire("203.0.113.7")).isPresent();
        assertThat(limiter.tryAcquire("198.51.100.9")).isEmpty();
    }

    @Test
    void idleClientsAreDroppedOnceTheTableIsFull() {
        ClientRateLimiter limiter = limiter(2, 60, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("b");
        limiter.tryAcquire("b");

        clock.advance(Duration.ofSeconds(1));
        limiter.tryAcquire("c");

        // a refilled and was dropped; b is still short of a full bucket, so it keeps its state.
        assertThat(limiter.clients()).isEqualTo(2);
        assertThat(limiter.tryAcquire("b")).isEmpty();
        assertThat(limiter.tryAcquire("b")).isPresent();
    }

    @Test
    void theClientIsTheFirstForwardedForHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        assertThat(RateLimitInterceptor.clientAddress(request)).isEqualTo("10.0.0.5");

        request.addHeader("X-Forwarded-For", " 203.0.113.7 , 10.0.0.1");
        assertThat(RateLimitInterceptor.clientAddress(request)).isEqualTo("203.0.113.7");
    }

    private ClientRateLimiter limiter(int burst, int perMinute, int maxClients) {
        return new ClientRateLimiter(new WebProperties.RateLimit(true, burst, perMinute, maxClients), clock);
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
