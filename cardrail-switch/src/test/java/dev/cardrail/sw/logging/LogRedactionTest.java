package dev.cardrail.sw.logging;

import dev.cardrail.sw.domain.card.Luhn;
import dev.cardrail.sw.domain.card.PanMasking;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactionTest {

    @Test
    void masksPansButLeavesVaultTokensReadable() {
        assertThat(LogRedaction.redact("card=5555555555554444 token=9999990000000006 amount=20000"))
                .isEqualTo("card=555555******4444 token=9999990000000006 amount=20000");
    }

    @Test
    void masksGroupedPans() {
        assertThat(LogRedaction.redact("pan 5555 5555 5555 4444 entered")).isEqualTo("pan 5555 55** **** 4444 entered");
    }

    @Test
    void masksNineteenDigitDevTokensBecauseTheyAreNotInTheVaultFormat() {
        String devToken = "999999" + "123456789012";
        devToken = devToken + Luhn.checkDigit(devToken);
        assertThat(LogRedaction.redact("token " + devToken)).doesNotContain(devToken);
    }

    @Test
    void leavesNonPanNumbersAlone() {
        String text = "stan=000123 rrn=000000000042 bad=5555555555554445 long=55555555555544445555";
        assertThat(LogRedaction.redact(text)).isEqualTo(text);
    }

    @Property
    void noLuhnValidPanSurvives(@ForAll("pans") String pan, @ForAll("text") String prefix,
                                @ForAll("text") String suffix) {
        String redacted = LogRedaction.redact(prefix + " " + pan + " " + suffix);
        assertThat(redacted).doesNotContain(pan).contains(PanMasking.mask(pan));
    }

    @Provide
    Arbitrary<String> pans() {
        return Arbitraries.integers().between(12, 18)
                .flatMap(length -> Arbitraries.strings().numeric().ofLength(length))
                .filter(payload -> !payload.startsWith("999999"))
                .map(payload -> payload + Luhn.checkDigit(payload));
    }

    @Provide
    Arbitrary<String> text() {
        return Arbitraries.strings().alpha().ofMaxLength(20);
    }
}
