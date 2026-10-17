package dev.cardrail.apisec;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, time-expiring {@link NonceStore} for a single JVM. Expired entries are evicted on every call in
 * expiry order (amortised O(log n)); when the store is full of live entries it refuses new ones rather than
 * evicting a live entry, which would reopen a replay window.
 *
 * <p>Uses a {@link ReentrantLock} rather than {@code synchronized} so callers on virtual threads do not pin
 * their carrier thread on Java 21.
 */
public final class InMemoryNonceStore implements NonceStore {

    private record Key(String consumerKey, String nonce) {
    }

    private record Expiry(Instant at, Key key) {
    }

    private final int maxEntries;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Key, Instant> live = new HashMap<>();
    private final PriorityQueue<Expiry> byExpiry = new PriorityQueue<>(Comparator.comparing(Expiry::at));

    public InMemoryNonceStore(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public Outcome record(String consumerKey, String nonce, Instant expiresAt, Instant now) {
        Key key = new Key(consumerKey, nonce);
        lock.lock();
        try {
            evictExpired(now);
            if (live.containsKey(key)) {
                return Outcome.ALREADY_SEEN;
            }
            if (live.size() >= maxEntries) {
                return Outcome.CAPACITY_EXHAUSTED;
            }
            live.put(key, expiresAt);
            byExpiry.add(new Expiry(expiresAt, key));
            return Outcome.RECORDED;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return live.size();
        } finally {
            lock.unlock();
        }
    }

    private void evictExpired(Instant now) {
        Expiry head;
        while ((head = byExpiry.peek()) != null && head.at().isBefore(now)) {
            byExpiry.poll();
            live.remove(head.key(), head.at());
        }
    }
}
