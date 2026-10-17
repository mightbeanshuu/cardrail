package dev.cardrail.vault;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PanRedactorTest {

    private final PanRedactor redactor = new PanRedactor();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "pan=5555555555554444 ok|pan=555555******4444 ok",
            "card 5555 5555 5555 4444 declined|card 5555 55** **** 4444 declined",
            "card 5555-5555-5555-4444|card 5555-55**-****-4444",
            "4111111111111111|411111******1111",
            "a 2223003122003222 b 5105105105105100|a 222300******3222 b 510510******5100",
            "5555555555554444 1000|555555******4444 1000",
            "amount 1000 5555555555554444|amount 1000 555555******4444",
    })
    void masksLuhnValidRuns(String input, String expected) {
        assertThat(redactor.redact(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "order 5555555555554445 shipped",
            "stan=123456 rrn=000000123456",
            "at 2026-10-17 12:34:56",
            "ts=20261017123457",
            "15555555555554444",
            "5555  5555  5555  4444x",
    })
    void leavesOtherDigitRunsUntouched(String input) {
        assertThat(redactor.redact(input)).isEqualTo(input);
    }

    @Test
    void tokenExemptRedactorKeepsTokensReadable() {
        TokenFormat format = new TokenFormat();
        String token = format.next();
        String line = "token=" + token + " pan=" + TestPans.MC_1;
        assertThat(new PanRedactor(format).redact(line)).isEqualTo("token=" + token + " pan=555555******4444");
        assertThat(redactor.redact(line)).doesNotContain(token);
    }

    @Property
    void leaksNoEmbeddedPan(@ForAll("pans") String pan, @ForAll("separators") String separator,
                            @ForAll("noise") String before, @ForAll("noise") String after,
                            @ForAll("boundaries") String left, @ForAll("boundaries") String right) {
        String rendered = separator.isEmpty() ? pan : TestPans.grouped(pan, separator.charAt(0));
        String text = before + left + rendered + right + after;

        String out = redactor.redact(text);

        assertThat(out).hasSameSizeAs(text).doesNotContain(rendered);
        int offset = before.length() + left.length();
        int digitIndex = 0;
        for (int i = 0; i < rendered.length(); i++) {
            if (!Luhn.isAsciiDigit(rendered.charAt(i))) {
                continue;
            }
            if (!PanMasker.isRevealed(digitIndex, pan.length())) {
                assertThat(out.charAt(offset + i)).as("digit %d of the PAN in %s", digitIndex, out).isEqualTo('*');
            }
            digitIndex++;
        }
    }

    @Property
    void leavesNonLuhnRunsInWordsUntouched(@ForAll("nonLuhn") String run, @ForAll("words") String before,
                                           @ForAll("words") String after) {
        String text = before + " " + run + " " + after;
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Provide
    Arbitrary<String> pans() {
        return TestPans.validPans();
    }

    @Provide
    Arbitrary<String> nonLuhn() {
        return TestPans.nonLuhnRuns();
    }

    @Provide
    Arbitrary<String> separators() {
        return Arbitraries.of("", " ", "-");
    }

    @Provide
    Arbitrary<String> boundaries() {
        return Arbitraries.of(" ", "=", ":", ",", "\n", "\t", "[", "]", "\"", " - ");
    }

    @Provide
    Arbitrary<String> noise() {
        return Arbitraries.strings().withChars("abcxyz0123456789 -=:,.").ofMaxLength(40);
    }

    @Provide
    Arbitrary<String> words() {
        return Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz=:,.").ofMaxLength(30);
    }
}
