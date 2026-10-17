package dev.cardrail.vault;

/**
 * Luhn (mod 10) check digit, as used by ISO/IEC 7812 card numbers.
 *
 * <p>Takes {@link CharSequence} so callers holding a PAN in a {@code char[]} can pass
 * {@code CharBuffer.wrap(digits)} without materialising a {@code String}.
 */
public final class Luhn {

    private Luhn() {
    }

    /** True if {@code number} (digits only, check digit last) passes the Luhn check. */
    public static boolean isValid(CharSequence number) {
        int length = number.length();
        if (length < 2) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (!isAsciiDigit(number.charAt(i))) {
                return false;
            }
        }
        // Including the check digit, the rightmost digit is not doubled.
        return weightedSum(number, length, false) % 10 == 0;
    }

    /**
     * Computes the check digit to append to {@code payload}.
     *
     * @throws IllegalArgumentException if {@code payload} is empty or contains a non-digit
     */
    public static int checkDigit(CharSequence payload) {
        int length = payload.length();
        if (length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        for (int i = 0; i < length; i++) {
            if (!isAsciiDigit(payload.charAt(i))) {
                throw new IllegalArgumentException("payload must contain only digits 0-9");
            }
        }
        // The check digit will occupy the rightmost position, so the payload's rightmost digit is doubled.
        return (10 - weightedSum(payload, length, true) % 10) % 10;
    }

    private static int weightedSum(CharSequence digits, int length, boolean doubleRightmost) {
        int sum = 0;
        boolean doubleIt = doubleRightmost;
        for (int i = length - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum;
    }

    static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
