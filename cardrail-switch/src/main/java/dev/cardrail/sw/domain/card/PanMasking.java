package dev.cardrail.sw.domain.card;

/**
 * Masks PANs as first 6 + last 4, the display form PCI DSS permits.
 */
public final class PanMasking {

    private static final int MIN_PAN_LENGTH = 13;

    private PanMasking() {
    }

    public static String mask(String pan) {
        if (pan.length() < MIN_PAN_LENGTH) {
            return "*".repeat(pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }
}
