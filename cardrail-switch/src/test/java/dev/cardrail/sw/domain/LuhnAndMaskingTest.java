package dev.cardrail.sw.domain;

import dev.cardrail.sw.domain.card.Luhn;
import dev.cardrail.sw.domain.card.PanMasking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnAndMaskingTest {

    @ParameterizedTest
    @ValueSource(strings = {"5555555555554444", "5105105105105100", "4111111111111111", "2223003122003222",
            "378282246310005"})
    void knownTestCardsPassLuhn(String pan) {
        assertThat(Luhn.isValid(pan)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"5555555555554445", "4111111111111112", "12a4", "", "0"})
    void corruptedNumbersFailLuhn(String value) {
        assertThat(Luhn.isValid(value)).isFalse();
    }

    @Test
    void checkDigitCompletesAValidNumber() {
        assertThat(Luhn.checkDigit("555555555555444")).isEqualTo('4');
    }

    @Test
    void maskKeepsFirstSixAndLastFour() {
        assertThat(PanMasking.mask("5555555555554444")).isEqualTo("555555******4444");
        assertThat(PanMasking.mask("2223003122003222")).isEqualTo("222300******3222");
    }
}
