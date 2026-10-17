package dev.cardrail.loadgen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size log-linear histogram of non-negative longs (latencies in nanoseconds here).
 *
 * <p>Each power-of-two range {@code [2^k, 2^(k+1))} is split into {@code 2^precisionBits} equal
 * buckets, and values below {@code 2^(precisionBits+1)} get a bucket each. A reported percentile is
 * the upper bound of the bucket holding the exact value (capped at the recorded maximum), so it is
 * never below the exact value and exceeds it by less than {@code exact / 2^precisionBits}. Memory is
 * fixed: {@code (64 - precisionBits) * 2^precisionBits} counters, whatever is recorded.
 *
 * <p>Recording is lock-free and safe from any number of threads.
 */
public final class LatencyHistogram {

    public static final int DEFAULT_PRECISION_BITS = 7;

    private final int precisionBits;
    private final AtomicLongArray counts;
    private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);

    public LatencyHistogram() {
        this(DEFAULT_PRECISION_BITS);
    }

    public LatencyHistogram(int precisionBits) {
        if (precisionBits < 1 || precisionBits > 14) {
            throw new IllegalArgumentException("precisionBits must be 1-14, got " + precisionBits);
        }
        this.precisionBits = precisionBits;
        this.counts = new AtomicLongArray((64 - precisionBits) << precisionBits);
    }

    public int precisionBits() {
        return precisionBits;
    }

    public void record(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value " + value);
        }
        counts.incrementAndGet(bucketIndex(value));
        max.accumulateAndGet(value, Math::max);
        min.accumulateAndGet(value, Math::min);
    }

    /*
     * Bucket index = e * 2^p + (v >>> e), where e = max(0, msb(v) - p). For e = 0 that is v itself; for
     * e >= 1, v >>> e lies in [2^p, 2^(p+1)), so each e owns the next 2^p indices without gaps.
     */
    int bucketIndex(long value) {
        int shift = Math.max(0, 63 - Long.numberOfLeadingZeros(value) - precisionBits);
        return (shift << precisionBits) + (int) (value >>> shift);
    }

    long bucketUpperBound(int index) {
        int shift = Math.max(0, (index >>> precisionBits) - 1);
        long mantissa = index - ((long) shift << precisionBits);
        // For the top bucket (mantissa + 1) << shift is 2^63, which wraps to Long.MIN_VALUE; subtracting
        // one wraps back to Long.MAX_VALUE, which is that bucket's true upper bound.
        return ((mantissa + 1) << shift) - 1;
    }

    public long count() {
        long n = 0;
        for (int i = 0; i < counts.length(); i++) {
            n += counts.get(i);
        }
        return n;
    }

    /** Exact largest recorded value; 0 if nothing was recorded. */
    public long max() {
        return count() == 0 ? 0 : max.get();
    }

    /** Exact smallest recorded value; 0 if nothing was recorded. */
    public long min() {
        // Emptiness comes from the counts: the sentinel is itself a value that can be recorded.
        return count() == 0 ? 0 : min.get();
    }

    /**
     * Nearest-rank percentile: the value at rank {@code ceil(percentile / 100 * count)} (at least 1)
     * of the sorted recordings, to bucket precision.
     *
     * @param percentile greater than 0 and at most 100
     * @return 0 if nothing was recorded
     */
    public long valueAtPercentile(double percentile) {
        if (!(percentile > 0 && percentile <= 100)) {
            throw new IllegalArgumentException("percentile must be in (0, 100], got " + percentile);
        }
        long[] snapshot = new long[counts.length()];
        long total = 0;
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = counts.get(i);
            total += snapshot[i];
        }
        if (total == 0) {
            return 0;
        }
        long maxSeen = max.get();
        long rank = nearestRank(percentile, total);
        long cumulative = 0;
        for (int i = 0; i < snapshot.length; i++) {
            cumulative += snapshot[i];
            if (cumulative >= rank) {
                return Math.min(bucketUpperBound(i), maxSeen);
            }
        }
        return maxSeen;
    }

    /** 1-based rank of the nearest-rank percentile; decimal arithmetic so 99.9% of 1000 is rank 999, not 1000. */
    static long nearestRank(double percentile, long count) {
        long rank = BigDecimal.valueOf(percentile).multiply(BigDecimal.valueOf(count))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact();
        return Math.max(1, rank);
    }
}
