package dev.cardrail.loadgen;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyHistogramPropertyTest {

    @Property
    void percentilesAgreeWithTheSortedSampleWithinBucketPrecision(
            @ForAll("samples") List<Long> values,
            @ForAll("percentiles") double percentile,
            @ForAll @IntRange(min = 1, max = 10) int precisionBits) {
        LatencyHistogram h = new LatencyHistogram(precisionBits);
        values.forEach(h::record);
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        long exact = sorted[(int) LatencyHistogram.nearestRank(percentile, sorted.length) - 1];

        long reported = h.valueAtPercentile(percentile);

        assertThat(reported).isGreaterThanOrEqualTo(exact);
        assertThat(reported - exact).isLessThanOrEqualTo(exact >>> precisionBits);
    }

    @Property
    void countMinAndMaxAreExact(@ForAll("samples") List<Long> values) {
        LatencyHistogram h = new LatencyHistogram();
        values.forEach(h::record);
        assertThat(h.count()).isEqualTo(values.size());
        assertThat(h.min()).isEqualTo(values.stream().mapToLong(Long::longValue).min().orElseThrow());
        assertThat(h.max()).isEqualTo(values.stream().mapToLong(Long::longValue).max().orElseThrow());
        assertThat(h.valueAtPercentile(100)).isEqualTo(h.max());
    }

    @Property
    void bucketsTileTheRangeWithoutGaps(@ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long value,
                                        @ForAll @IntRange(min = 1, max = 14) int precisionBits) {
        LatencyHistogram h = new LatencyHistogram(precisionBits);
        int index = h.bucketIndex(value);
        long upper = h.bucketUpperBound(index);
        assertThat(upper).isGreaterThanOrEqualTo(value);
        assertThat(h.bucketIndex(upper)).isEqualTo(index);
        if (upper < Long.MAX_VALUE) {
            assertThat(h.bucketIndex(upper + 1)).isEqualTo(index + 1);
        }
        if (index > 0) {
            assertThat(h.bucketUpperBound(index - 1)).isLessThan(value);
        }
    }

    @Property
    void nearestRankUsesDecimalArithmetic(@ForAll @Size(min = 1, max = 5000) List<Integer> ignored) {
        int n = ignored.size();
        assertThat(LatencyHistogram.nearestRank(100, n)).isEqualTo(n);
        assertThat(LatencyHistogram.nearestRank(50, n)).isEqualTo((n + 1) / 2);
    }

    @Test
    void ninetyNinePointNineOfOneThousandIsRank999() {
        assertThat(LatencyHistogram.nearestRank(99.9, 1000)).isEqualTo(999);
    }

    @Test
    void smallValuesAreExact() {
        LatencyHistogram h = new LatencyHistogram(7);
        long[] values = {0, 1, 2, 3, 100, 200, 255};
        Arrays.stream(values).forEach(h::record);
        assertThat(h.valueAtPercentile(70)).isEqualTo(100);
        assertThat(h.valueAtPercentile(1)).isEqualTo(0);
        assertThat(h.valueAtPercentile(100)).isEqualTo(255);
    }

    @Test
    void emptyHistogramReportsZeroAndBadInputIsRejected() {
        LatencyHistogram h = new LatencyHistogram();
        assertThat(h.valueAtPercentile(99)).isZero();
        assertThat(h.max()).isZero();
        assertThatThrownBy(() -> h.record(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.valueAtPercentile(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.valueAtPercentile(100.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<List<Long>> samples() {
        Arbitrary<Long> value = Arbitraries.oneOf(
                Arbitraries.longs().between(0, 300),
                Arbitraries.longs().between(50_000, 5_000_000),
                Arbitraries.longs().between(0, Long.MAX_VALUE));
        return value.list().ofMinSize(1).ofMaxSize(2000);
    }

    @Provide
    Arbitrary<Double> percentiles() {
        return Arbitraries.oneOf(
                Arbitraries.of(50.0, 90.0, 99.0, 99.9, 99.99, 100.0),
                Arbitraries.doubles().between(0.0, false, 100.0, true));
    }
}
