package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class LuhnTest {

    @ParameterizedTest
    @ValueSource(strings = {TestPans.MC_1, TestPans.MC_2, TestPans.MC_2_SERIES, TestPans.VISA,
            "378282246310005", "79927398713"})
    void acceptsKnownValidNumbers(String number) {
        assertThat(Luhn.isValid(number)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"5555555555554445", "4111111111111112", "79927398710", "0", "", "4111-1111"})
    void rejectsInvalidNumbers(String number) {
        assertThat(Luhn.isValid(number)).isFalse();
    }

    @Test
    void computesCheckDigitOfKnownVectors() {
        assertThat(Luhn.checkDigit("555555555555444")).isEqualTo(4);
        assertThat(Luhn.checkDigit("510510510510510")).isZero();
        assertThat(Luhn.checkDigit("222300312200322")).isEqualTo(2);
        assertThat(Luhn.checkDigit("7992739871")).isEqualTo(3);
    }

    @Test
    void checkDigitRejectsNonDigits() {
        assertThatIllegalArgumentException().isThrownBy(() -> Luhn.checkDigit("12a4"));
        assertThatIllegalArgumentException().isThrownBy(() -> Luhn.checkDigit(""));
    }

    @Property
    void appendingTheComputedCheckDigitValidates(@ForAll @NumericChars @StringLength(min = 1, max = 30) String payload) {
        assertThat(Luhn.isValid(payload + Luhn.checkDigit(payload))).isTrue();
    }

    @Property
    void everyOtherCheckDigitFails(@ForAll @NumericChars @StringLength(min = 1, max = 30) String payload,
                                   @ForAll @IntRange(min = 1, max = 9) int delta) {
        int wrong = (Luhn.checkDigit(payload) + delta) % 10;
        assertThat(Luhn.isValid(payload + wrong)).isFalse();
    }
}
