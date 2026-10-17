package dev.cardrail.vault;

import java.nio.CharBuffer;
import java.util.Arrays;
import javax.security.auth.Destroyable;

/**
 * A validated primary account number: 13-19 ASCII digits passing the Luhn check.
 *
 * <p>{@link #toString()} returns the masked form, so a {@code Pan} that reaches a log statement,
 * an exception message or a debugger watch shows {@code 555555******4444}, never the full number.
 * The full digits are only reachable through {@link #exposeFullPanUnsafe()}, named so that every
 * call site stands out in review.
 *
 * <p>Digits are held in a {@code char[]} that {@link #destroy()} overwrites. That is a best-effort
 * reduction of the PAN's lifetime in memory, not a guarantee: a PAN that ever existed as a
 * {@code String} (for example the ISO 8583 field it was parsed from) cannot be wiped, because Java
 * Strings are immutable and the garbage collector may have copied any array before it was cleared.
 *
 * <p>Not thread-safe with respect to {@code destroy()}; a {@code Pan} is meant to live inside a
 * single request.
 */
public final class Pan implements Destroyable {

    public static final int MIN_LENGTH = 13;
    public static final int MAX_LENGTH = 19;

    private final char[] digits;
    private final String masked;
    private boolean destroyed;

    private Pan(char[] digits) {
        this.digits = digits;
        this.masked = PanMasker.mask(CharBuffer.wrap(digits));
    }

    /** Parses digits only (no spaces or dashes). The caller's sequence is not retained. */
    public static Pan of(CharSequence raw) {
        char[] copy = new char[raw.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = raw.charAt(i);
        }
        return validated(copy);
    }

    /** As {@link #of(CharSequence)}; copies {@code raw}, so the caller may wipe its array afterwards. */
    public static Pan of(char[] raw) {
        return validated(raw.clone());
    }

    private static Pan validated(char[] candidate) {
        try {
            if (candidate.length < MIN_LENGTH || candidate.length > MAX_LENGTH) {
                throw new InvalidCardNumberException(
                        "card number must be " + MIN_LENGTH + "-" + MAX_LENGTH + " digits, got " + candidate.length);
            }
            for (char c : candidate) {
                if (!Luhn.isAsciiDigit(c)) {
                    throw new InvalidCardNumberException("card number must contain only digits 0-9");
                }
            }
            if (!Luhn.isValid(CharBuffer.wrap(candidate))) {
                throw new InvalidCardNumberException("card number fails the Luhn check");
            }
        } catch (InvalidCardNumberException e) {
            Arrays.fill(candidate, '\0');
            throw e;
        }
        return new Pan(candidate);
    }

    /** First six digits, e.g. {@code 555555}. */
    public String bin() {
        return masked.substring(0, PanMasker.VISIBLE_PREFIX);
    }

    public String last4() {
        return masked.substring(masked.length() - PanMasker.VISIBLE_SUFFIX);
    }

    /** {@code 555555******4444}; length-preserving. */
    public String masked() {
        return masked;
    }

    public int length() {
        return masked.length();
    }

    public CardScheme scheme() {
        return CardScheme.detect(masked);
    }

    /**
     * Returns a fresh copy of the full digits. The caller owns the copy and should
     * {@code Arrays.fill(copy, '\0')} it as soon as it is no longer needed.
     *
     * @throws IllegalStateException if this Pan has been destroyed
     */
    public char[] exposeFullPanUnsafe() {
        if (destroyed) {
            throw new IllegalStateException("Pan has been destroyed");
        }
        return digits.clone();
    }

    /** Overwrites the digits. The masked form stays available; every other accessor stays usable. */
    @Override
    public void destroy() {
        Arrays.fill(digits, '\0');
        destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pan other) || destroyed || other.destroyed || digits.length != other.digits.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < digits.length; i++) {
            diff |= digits[i] ^ other.digits[i];
        }
        return diff == 0;
    }

    // Derived from the masked form: consistent with equals, and computes nothing from the hidden digits.
    @Override
    public int hashCode() {
        return masked.hashCode();
    }

    @Override
    public String toString() {
        return masked;
    }
}
