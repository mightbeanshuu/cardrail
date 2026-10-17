package dev.cardrail.vault;

/**
 * The single masking rule for the module: the first six digits (BIN) and the last four stay
 * visible, everything between becomes {@value #MASK_CHAR}. This is the maximum PCI DSS permits to be
 * displayed; showing less is always allowed, showing more is not.
 */
public final class PanMasker {

    public static final int VISIBLE_PREFIX = 6;
    public static final int VISIBLE_SUFFIX = 4;
    public static final char MASK_CHAR = '*';

    private PanMasker() {
    }

    /**
     * Masks a digit string of at least {@code VISIBLE_PREFIX + VISIBLE_SUFFIX + 1} characters,
     * preserving its length: {@code 5555555555554444 -> 555555******4444}.
     */
    public static String mask(CharSequence digits) {
        int length = digits.length();
        if (length <= VISIBLE_PREFIX + VISIBLE_SUFFIX) {
            throw new IllegalArgumentException("too short to mask without revealing it whole: length " + length);
        }
        StringBuilder masked = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            masked.append(isRevealed(i, length) ? digits.charAt(i) : MASK_CHAR);
        }
        return masked.toString();
    }

    /** Whether digit {@code index} of a {@code length}-digit PAN may be shown. */
    public static boolean isRevealed(int index, int length) {
        return index < VISIBLE_PREFIX || index >= length - VISIBLE_SUFFIX;
    }
}
