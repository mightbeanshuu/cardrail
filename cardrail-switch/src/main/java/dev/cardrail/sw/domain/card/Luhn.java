package dev.cardrail.sw.domain.card;

public final class Luhn {

    private Luhn() {
    }

    /** True if {@code digits[from, to)} are all ASCII digits and pass the mod-10 check. */
    public static boolean isValid(CharSequence digits, int from, int to) {
        if (to - from < 2) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = to - 1; i >= from; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int d = c - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    public static boolean isValid(CharSequence digits) {
        return isValid(digits, 0, digits.length());
    }

    /** Check digit that makes {@code payload + checkDigit} Luhn-valid. */
    public static char checkDigit(CharSequence payload) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = payload.charAt(i) - '0';
            if (d < 0 || d > 9) {
                throw new IllegalArgumentException("payload must be digits only");
            }
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}
