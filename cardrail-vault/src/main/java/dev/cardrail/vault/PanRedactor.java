package dev.cardrail.vault;

import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * Masks card numbers in free text before it reaches a log sink.
 *
 * <p>A candidate is a run of 13-19 digits, optionally grouped by single spaces or dashes
 * ({@code 5555 5555 5555 4444}, {@code 5555-5555-5555-4444}). Each candidate that passes Luhn is
 * masked in place with the {@link PanMasker} rule, separators preserved. Digit runs that fail Luhn
 * (order ids, STANs, timestamps) are left alone.
 *
 * <p>Within a longer separator-joined run every group-aligned window of 13-19 digits is tested,
 * and the union of all passing windows is masked. That way a PAN followed by an amount
 * ({@code 5555555555554444 1000}) is still caught, and a chance Luhn match on a neighbouring window
 * can only mask more, never expose part of the real PAN. The trade-off is deliberate: about one in
 * ten unrelated 13-19 digit numbers passes Luhn by chance and gets masked too.
 *
 * <p>Known limit: digits glued to a PAN with no separator ({@code 15555555555554444}) form a
 * different, longer number and are not treated as containing a PAN.
 */
public final class PanRedactor {

    private static final int MIN_DIGITS = Pan.MIN_LENGTH;
    private static final int MAX_DIGITS = Pan.MAX_LENGTH;

    private final TokenFormat exemptTokens;

    /** Masks every Luhn-valid candidate, including this vault's own tokens. */
    public PanRedactor() {
        this.exemptTokens = null;
    }

    /**
     * Masks every Luhn-valid candidate except tokens of {@code tokens}' format, which stay readable
     * so log lines can still be correlated by token.
     */
    public PanRedactor(TokenFormat tokens) {
        this.exemptTokens = tokens;
    }

    public String redact(CharSequence text) {
        int n = text.length();
        char[] out = null;
        int i = 0;
        while (i < n) {
            if (!Luhn.isAsciiDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int runEnd = scanRun(text, i);
            boolean[] mask = maskForRun(text, i, runEnd);
            if (mask != null) {
                if (out == null) {
                    out = new char[n];
                    for (int k = 0; k < n; k++) {
                        out[k] = text.charAt(k);
                    }
                }
                for (int k = i; k < runEnd; k++) {
                    if (mask[k - i]) {
                        out[k] = PanMasker.MASK_CHAR;
                    }
                }
            }
            i = runEnd;
        }
        return out == null ? text.toString() : new String(out);
    }

    /** End (exclusive) of the digit run starting at {@code start}, following single separators. */
    private static int scanRun(CharSequence text, int start) {
        int n = text.length();
        int j = start;
        while (true) {
            while (j < n && Luhn.isAsciiDigit(text.charAt(j))) {
                j++;
            }
            if (j + 1 < n && isSeparator(text.charAt(j)) && Luhn.isAsciiDigit(text.charAt(j + 1))) {
                j++;
            } else {
                return j;
            }
        }
    }

    /**
     * Returns which characters of {@code text[start, end)} to mask, or null if none. Windows start
     * and end on group boundaries; the digits of each are copied into a scratch buffer for the Luhn
     * test and that buffer is cleared before returning.
     */
    private boolean[] maskForRun(CharSequence text, int start, int end) {
        int[] groupStart = new int[end - start];
        int[] groupEnd = new int[end - start];
        int groups = 0;
        for (int j = start; j < end; ) {
            groupStart[groups] = j;
            while (j < end && Luhn.isAsciiDigit(text.charAt(j))) {
                j++;
            }
            groupEnd[groups++] = j;
            j++; // skip the separator
        }

        boolean[] mask = null;
        char[] window = new char[MAX_DIGITS];
        try {
            for (int first = 0; first < groups; first++) {
                int count = 0;
                for (int last = first; last < groups; last++) {
                    int groupLength = groupEnd[last] - groupStart[last];
                    if (count + groupLength > MAX_DIGITS) {
                        break;
                    }
                    for (int k = groupStart[last]; k < groupEnd[last]; k++) {
                        window[count++] = text.charAt(k);
                    }
                    if (count < MIN_DIGITS) {
                        continue;
                    }
                    CharBuffer digits = CharBuffer.wrap(window, 0, count);
                    if (!Luhn.isValid(digits) || (exemptTokens != null && exemptTokens.isToken(digits))) {
                        continue;
                    }
                    if (mask == null) {
                        mask = new boolean[end - start];
                    }
                    markHiddenDigits(text, groupStart[first], groupEnd[last], count, start, mask);
                }
            }
        } finally {
            Arrays.fill(window, '\0');
        }
        return mask;
    }

    private static void markHiddenDigits(CharSequence text, int from, int to, int digitCount, int runStart,
                                         boolean[] mask) {
        int digitIndex = 0;
        for (int k = from; k < to; k++) {
            if (Luhn.isAsciiDigit(text.charAt(k))) {
                if (!PanMasker.isRevealed(digitIndex, digitCount)) {
                    mask[k - runStart] = true;
                }
                digitIndex++;
            }
        }
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '-';
    }
}
