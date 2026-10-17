package dev.cardrail.vault;

/**
 * Card scheme inferred from the leading digits (IIN) of a PAN.
 *
 * <p>This is a coarse, prefix-only classification for display and routing hints. RuPay's ranges
 * overlap Discover/Diners (60, 65) and others, so a prefix alone cannot be authoritative; a real
 * switch resolves schemes from the BIN tables each network distributes to its members.
 */
public enum CardScheme {
    MASTERCARD,
    VISA,
    AMEX,
    RUPAY,
    UNKNOWN;

    /**
     * Classifies by the leading digits of {@code digits}; only the first six are examined, so a BIN
     * or a masked PAN works as well as a full PAN.
     */
    public static CardScheme detect(CharSequence digits) {
        int available = Math.min(6, digits.length());
        int prefixLength = 0;
        while (prefixLength < available && Luhn.isAsciiDigit(digits.charAt(prefixLength))) {
            prefixLength++;
        }
        if (prefixLength == 0) {
            return UNKNOWN;
        }
        int p1 = prefix(digits, 1);
        int p2 = prefixLength >= 2 ? prefix(digits, 2) : -1;
        int p3 = prefixLength >= 3 ? prefix(digits, 3) : -1;
        int p4 = prefixLength >= 4 ? prefix(digits, 4) : -1;

        if ((p2 >= 51 && p2 <= 55) || (p4 >= 2221 && p4 <= 2720)) {
            return MASTERCARD;
        }
        if (p1 == 4) {
            return VISA;
        }
        if (p2 == 34 || p2 == 37) {
            return AMEX;
        }
        if (p2 == 60 || p2 == 65 || p2 == 81 || p2 == 82 || p3 == 508) {
            return RUPAY;
        }
        return UNKNOWN;
    }

    private static int prefix(CharSequence digits, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = value * 10 + (digits.charAt(i) - '0');
        }
        return value;
    }
}
