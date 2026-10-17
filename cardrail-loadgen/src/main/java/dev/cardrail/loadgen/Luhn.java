package dev.cardrail.loadgen;

/** Luhn (mod 10) check digit, as used for card numbers (ISO/IEC 7812-1 Annex B). */
public final class Luhn {

    private Luhn() {
    }

    public static boolean isValid(String digits) {
        if (digits.length() < 2 || !allDigits(digits)) {
            return false;
        }
        return checkDigit(digits.substring(0, digits.length() - 1)) == digits.charAt(digits.length() - 1) - '0';
    }

    /** The digit that makes {@code payload + digit} Luhn-valid. */
    public static int checkDigit(String payload) {
        if (payload.isEmpty() || !allDigits(payload)) {
            throw new IllegalArgumentException("payload must be a non-empty digit string");
        }
        int sum = 0;
        // Doubling starts at the rightmost payload digit, because the check digit will sit to its right.
        boolean doubled = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = payload.charAt(i) - '0';
            if (doubled) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubled = !doubled;
        }
        return (10 - sum % 10) % 10;
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
