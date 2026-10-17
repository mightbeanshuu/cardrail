package dev.cardrail.sw.adapter.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per client, in memory. Each instance limits on its own and forgets on restart,
 * which is enough for a courtesy limit on a single-instance demo.
 *
 * <p>A bucket is kept as the instant at which it would be full again (the generic cell rate
 * algorithm), which behaves exactly like counting tokens but needs no fractional arithmetic.
 */
final class ClientRateLimiter {

    private final Duration interval;
    private final Duration tolerance;
    private final int maxClients;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    ClientRateLimiter(WebProperties.RateLimit limit, Clock clock) {
        this.interval = Duration.ofMinutes(1).dividedBy(limit.requestsPerMinute());
        this.tolerance = interval.multipliedBy(limit.burst() - 1L);
        this.maxClients = limit.maxClients();
        this.clock = clock;
    }

    /** Empty when the request may go ahead, otherwise how long until the client has a token again. */
    Optional<Duration> tryAcquire(String client) {
        Instant now = clock.instant();
        if (buckets.size() >= maxClients && !buckets.containsKey(client)) {
            // A full bucket has the same state as a new one, so dropping it forgets nothing.
            buckets.values().removeIf(bucket -> bucket.isFull(now));
            if (buckets.size() >= maxClients) {
                buckets.clear();
            }
        }
        return buckets.computeIfAbsent(client, c -> new Bucket(now)).tryTake(now);
    }

    int clients() {
        return buckets.size();
    }

    private final class Bucket {

        private Instant fullAt;

        Bucket(Instant now) {
            this.fullAt = now;
        }

        synchronized Optional<Duration> tryTake(Instant now) {
            Instant from = fullAt.isAfter(now) ? fullAt : now;
            Duration debt = Duration.between(now, from);
            if (debt.compareTo(tolerance) > 0) {
                return Optional.of(debt.minus(tolerance));
            }
            fullAt = from.plus(interval);
            return Optional.empty();
        }

        synchronized boolean isFull(Instant now) {
            return !fullAt.isAfter(now);
        }
    }
}
