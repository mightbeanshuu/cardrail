package dev.cardrail.clearing.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InterchangeFeePropertiesTest {

    private static final long MAX_AMOUNT = 999_999_999_999L; // ISO 8583 field 4 is n12

    @Provide
    Arbitrary<InterchangeRate> rates() {
        return Combinators.combine(Arbitraries.integers().between(0, 10_000), Arbitraries.longs().between(0, 100_000))
                .as((bps, fixed) -> new InterchangeRate("ANY", bps, fixed));
    }

    @Property
    void feeIsNeverNegative(@ForAll("rates") InterchangeRate rate, @ForAll @LongRange(min = 0, max = MAX_AMOUNT) long amount) {
        assertThat(rate.feeFor(amount)).isNotNegative();
    }

    @Property
    void feeIsMonotonicInAmount(@ForAll("rates") InterchangeRate rate,
                                @ForAll @LongRange(min = 0, max = MAX_AMOUNT) long a,
                                @ForAll @LongRange(min = 0, max = MAX_AMOUNT) long b) {
        long low = Math.min(a, b);
        long high = Math.max(a, b);
        assertThat(rate.feeFor(low)).isLessThanOrEqualTo(rate.feeFor(high));
    }

    @Property
    void roundingErrorIsAtMostHalfAMinorUnit(@ForAll("rates") InterchangeRate rate,
                                             @ForAll @LongRange(min = 0, max = MAX_AMOUNT) long amount) {
        BigDecimal exact = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate.rateBps()))
                .divide(BigDecimal.valueOf(10_000)).add(BigDecimal.valueOf(rate.fixedFeeMinor()));
        BigDecimal error = BigDecimal.valueOf(rate.feeFor(amount)).subtract(exact).abs();
        assertThat(error).isLessThanOrEqualTo(new BigDecimal("0.5"));
    }

    @Property
    void feeNeverExceedsTheAmountPlusTheFixedFee(@ForAll("rates") InterchangeRate rate,
                                                 @ForAll @LongRange(min = 0, max = MAX_AMOUNT) long amount) {
        assertThat(rate.feeFor(amount)).isLessThanOrEqualTo(amount + rate.fixedFeeMinor());
    }
}
