package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PanTest {

    @Test
    void exposesBinLast4MaskedAndScheme() {
        Pan pan = Pan.of(TestPans.MC_1);
        assertThat(pan.bin()).isEqualTo("555555");
        assertThat(pan.last4()).isEqualTo("4444");
        assertThat(pan.masked()).isEqualTo("555555******4444");
        assertThat(pan.scheme()).isEqualTo(CardScheme.MASTERCARD);
        assertThat(pan.length()).isEqualTo(16);
        assertThat(String.valueOf(pan.exposeFullPanUnsafe())).isEqualTo(TestPans.MC_1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"555555555555444", "5555555555554445", "55555555555544x4", "5555 5555 5555 4444",
            "", "41111111111111111111"})
    void rejectsInvalidInputWithoutEchoingIt(String raw) {
        assertThatThrownBy(() -> Pan.of(raw))
                .isInstanceOf(InvalidCardNumberException.class)
                .satisfies(e -> {
                    if (!raw.isEmpty()) {
                        assertThat(e.getMessage()).doesNotContain(raw);
                    }
                });
    }

    @Test
    void ofCharArrayCopiesSoTheCallerMayWipe() {
        char[] raw = TestPans.VISA.toCharArray();
        Pan pan = Pan.of(raw);
        java.util.Arrays.fill(raw, '0');
        assertThat(String.valueOf(pan.exposeFullPanUnsafe())).isEqualTo(TestPans.VISA);
    }

    @Test
    void destroyWipesDigitsButKeepsMaskedForm() {
        Pan pan = Pan.of(TestPans.MC_1);
        pan.destroy();
        assertThat(pan.isDestroyed()).isTrue();
        assertThat(pan.toString()).isEqualTo("555555******4444");
        assertThatThrownBy(pan::exposeFullPanUnsafe).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void equalityIsByDigits() {
        assertThat(Pan.of(TestPans.MC_1)).isEqualTo(Pan.of(TestPans.MC_1)).hasSameHashCodeAs(Pan.of(TestPans.MC_1));
        assertThat(Pan.of(TestPans.MC_1)).isNotEqualTo(Pan.of(TestPans.MC_2));
    }

    @Property
    void toStringNeverRevealsTheFullPan(@ForAll("pans") String digits) {
        Pan pan = Pan.of(digits);
        String shown = pan.toString();
        assertThat(shown).doesNotContain(digits).hasSize(digits.length());
        assertThat(shown).startsWith(digits.substring(0, 6)).endsWith(digits.substring(digits.length() - 4));
        assertThat(shown.substring(6, shown.length() - 4)).matches("\\*+");
    }

    @Provide
    Arbitrary<String> pans() {
        return TestPans.validPans();
    }
}
