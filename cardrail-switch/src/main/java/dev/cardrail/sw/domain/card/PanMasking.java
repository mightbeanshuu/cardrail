package dev.cardrail.sw.domain.card;

/**
 * Masks PANs as first 6 + last 4, the display form PCI DSS permits.
 */
public final class PanMasking {

    private static final int MIN_PAN_LENGTH = 13;
    private static final int MAX_PAN_LENGTH = 19;

    private PanMasking() {
    }

    public static String mask(String pan) {
        if (pan.length() < MIN_PAN_LENGTH) {
            return "*".repeat(pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    /**
     * Replaces every run of 13 to 19 digits that passes Luhn with its masked form. Longer and shorter
     * runs are left alone. Some non-PAN numbers (one in ten 13-digit epoch-millis values, for example)
     * also pass Luhn and get masked; over-masking a log line is the acceptable failure mode here.
     */
    public static String maskPans(String text) {
        if (text == null || text.length() < MIN_PAN_LENGTH) {
            return text;
        }
        StringBuilder out = null;
        int copiedUpTo = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (!isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
            int length = i - start;
            if (length >= MIN_PAN_LENGTH && length <= MAX_PAN_LENGTH && Luhn.isValid(text, start, i)) {
                if (out == null) {
                    out = new StringBuilder(n);
                }
                out.append(text, copiedUpTo, start).append(mask(text.substring(start, i)));
                copiedUpTo = i;
            }
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copiedUpTo, n).toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
