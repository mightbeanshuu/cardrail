package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class TokenFormatTest {

    private final TokenFormat format = new TokenFormat();

    @Test
    void defaultsToTokenBin999999() {
        assertThat(format.tokenBin()).isEqualTo("999999");
        assertThat(format.next()).startsWith("999999");
    }

    @Property
    void tokensAreSixteenDigitLuhnValidAndInTheirBin(@ForAll("unassignedBins") String bin) {
        TokenFormat configured = new TokenFormat(bin);
        String token = configured.next();
        assertThat(token).hasSize(16).startsWith(bin).matches("\\d{16}");
        assertThat(Luhn.isValid(token)).isTrue();
        assertThat(configured.isToken(token)).isTrue();
        assertThat(Pan.of(token).scheme()).isEqualTo(CardScheme.UNKNOWN);
    }

    @Provide
    Arbitrary<String> unassignedBins() {
        return Arbitraries.strings().numeric().ofLength(6).filter(bin -> CardScheme.detect(bin) == CardScheme.UNKNOWN);
    }

    @Test
    void rejectsBinInsideARealSchemeRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TokenFormat("555555")).withMessageContaining("MASTERCARD");
        assertThatIllegalArgumentException().isThrownBy(() -> new TokenFormat("411111"));
        assertThatIllegalArgumentException().isThrownBy(() -> new TokenFormat("99999"));
    }

    @Test
    void realPansAreNotTokens() {
        assertThat(format.isToken(TestPans.MC_1)).isFalse();
        assertThat(format.isToken("9999990000000000")).isFalse(); // right BIN, bad Luhn
    }
}
