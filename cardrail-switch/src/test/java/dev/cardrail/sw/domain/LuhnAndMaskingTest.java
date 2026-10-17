package dev.cardrail.sw.domain;

import dev.cardrail.sw.domain.card.Luhn;
import dev.cardrail.sw.domain.card.PanMasking;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
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

    @Test
    void masksLuhnValidRunsInsideText() {
        assertThat(PanMasking.maskPans("card=5555555555554444, amount=20000"))
                .isEqualTo("card=555555******4444, amount=20000");
    }

    @Test
    void leavesNonPanNumbersAlone() {
        String text = "stan=000123 rrn=000000000042 bad=5555555555554445 long=55555555555544445555";
        assertThat(PanMasking.maskPans(text)).isEqualTo(text);
    }

    @Property
    void noLuhnValidPanSurvivesMasking(@ForAll("pans") String pan, @ForAll("text") String prefix,
                                       @ForAll("text") String suffix) {
        String masked = PanMasking.maskPans(prefix + " " + pan + " " + suffix);
        assertThat(masked).doesNotContain(pan).contains(PanMasking.mask(pan));
    }

    @Provide
    Arbitrary<String> pans() {
        return Arbitraries.integers().between(12, 18)
                .flatMap(length -> Arbitraries.strings().numeric().ofLength(length))
                .map(payload -> payload + Luhn.checkDigit(payload));
    }

    @Provide
    Arbitrary<String> text() {
        return Arbitraries.strings().alpha().ofMaxLength(20);
    }
}
