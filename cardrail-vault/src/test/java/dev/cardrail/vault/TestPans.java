package dev.cardrail.vault;

import java.nio.CharBuffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

final class TestPans {

    static final String MC_1 = "5555555555554444";
    static final String MC_2 = "5105105105105100";
    static final String MC_2_SERIES = "2223003122003222";
    static final String VISA = "4111111111111111";

    private TestPans() {
    }

    /** Luhn-valid digit strings of 13-19 digits, not starting with the default token BIN. */
    static Arbitrary<String> validPans() {
        return Arbitraries.integers().between(Pan.MIN_LENGTH, Pan.MAX_LENGTH)
                .flatMap(length -> Arbitraries.strings().numeric().ofLength(length - 1))
                .filter(payload -> !payload.startsWith(TokenFormat.DEFAULT_TOKEN_BIN))
                .map(TestPans::withCheckDigit);
    }

    /** Digit strings of 13-19 digits that fail Luhn. */
    static Arbitrary<String> nonLuhnRuns() {
        return Combinators.combine(validPans(), Arbitraries.integers().between(1, 9))
                .as((pan, delta) -> {
                    char last = pan.charAt(pan.length() - 1);
                    char wrong = (char) ('0' + (last - '0' + delta) % 10);
                    return pan.substring(0, pan.length() - 1) + wrong;
                });
    }

    static String withCheckDigit(String payload) {
        return payload + Luhn.checkDigit(payload);
    }

    /** Groups of four separated by {@code separator}; the last group holds the remainder. */
    static String grouped(String digits, char separator) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(separator);
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    static boolean luhn(char[] digits) {
        return Luhn.isValid(CharBuffer.wrap(digits));
    }
}
