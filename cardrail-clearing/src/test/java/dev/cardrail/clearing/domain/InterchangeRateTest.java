package dev.cardrail.clearing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterchangeRateTest {

    @ParameterizedTest(name = "{0} at {1} bps + {2} = {3}")
    @CsvSource({
            "20000,  110, 0,    220",
            "300000,  50, 500,  2000",
            "45050,  150, 0,    676",
            "125000, 190, 1000, 3375",
            // exact halves round to even
            "250,     20, 0,    0",
            "750,     20, 0,    2",
            "1250,    20, 0,    2",
            "1251,    20, 0,    3",
            "0,      175, 100,  100",
            "1,    10000, 0,    1"
    })
    void feeIsRoundHalfEvenOfTheProportionalPartPlusTheFixedFee(long amount, int bps, long fixed, long expected) {
        assertThat(new InterchangeRate("TEST", bps, fixed).feeFor(amount)).isEqualTo(expected);
    }

    @Test
    void largestIsoAmountDoesNotOverflow() {
        assertThat(new InterchangeRate("TEST", 10_000, 0).feeFor(999_999_999_999L)).isEqualTo(999_999_999_999L);
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> new InterchangeRate("TEST", -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterchangeRate("TEST", 10_001, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterchangeRate("TEST", 100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterchangeRate("TEST", 100, 0).feeFor(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
