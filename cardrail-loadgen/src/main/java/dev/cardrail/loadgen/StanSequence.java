package dev.cardrail.loadgen;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * System trace audit numbers (field 11): {@code 000001} to {@code 999999}, then back to {@code 000001}.
 * Zero is skipped. Thread-safe.
 */
public final class StanSequence {

    public static final int MAX = 999_999;

    private final AtomicInteger last;

    public StanSequence() {
        this(1);
    }

    /** @param first the first STAN {@link #next()} returns, 1 to {@link #MAX} */
    public StanSequence(int first) {
        if (first < 1 || first > MAX) {
            throw new IllegalArgumentException("first STAN must be 1-" + MAX + ", got " + first);
        }
        this.last = new AtomicInteger(first - 1);
    }

    public int nextValue() {
        return last.updateAndGet(v -> v >= MAX ? 1 : v + 1);
    }

    public String next() {
        return format(nextValue());
    }

    static String format(int stan) {
        return String.format("%06d", stan);
    }
}
