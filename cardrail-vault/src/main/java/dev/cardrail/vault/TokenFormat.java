package dev.cardrail.vault;

import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Format-preserving surrogate card numbers: 16 digits = 6-digit token BIN + 9 random digits +
 * Luhn check digit. Downstream systems that validate length and Luhn accept a token where they
 * would accept a PAN, and the reserved BIN tells anyone reading it that it is not a real card.
 *
 * <p>Nine random digits give 10^9 tokens per token BIN; uniqueness is enforced by the store at
 * insert, not assumed. This models vault mechanics only. Real network tokens (for example
 * Mastercard MDES) are issued from network-assigned token BIN ranges and carry domain restrictions
 * (merchant, channel, device) enforced by the network at authorization time.
 */
public final class TokenFormat {

    public static final String DEFAULT_TOKEN_BIN = "999999";
    public static final int TOKEN_LENGTH = 16;

    private final String tokenBin;
    private final RandomGenerator random;

    public TokenFormat() {
        this(DEFAULT_TOKEN_BIN);
    }

    public TokenFormat(String tokenBin) {
        this(tokenBin, new SecureRandom());
    }

    /** Test seam: lets a test script the "random" digits to force collisions. */
    TokenFormat(String tokenBin, RandomGenerator random) {
        if (tokenBin.length() != PanMasker.VISIBLE_PREFIX || !tokenBin.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("token BIN must be exactly 6 digits");
        }
        // A token BIN inside a live scheme range would make tokens indistinguishable from real cards.
        if (CardScheme.detect(tokenBin) != CardScheme.UNKNOWN) {
            throw new IllegalArgumentException("token BIN " + tokenBin + " falls in the "
                    + CardScheme.detect(tokenBin) + " range; choose an unassigned range");
        }
        this.tokenBin = tokenBin;
        this.random = random;
    }

    public String tokenBin() {
        return tokenBin;
    }

    /** A fresh random token. Not guaranteed unique; the caller must collision-check on insert. */
    public String next() {
        char[] token = new char[TOKEN_LENGTH];
        tokenBin.getChars(0, tokenBin.length(), token, 0);
        for (int i = tokenBin.length(); i < TOKEN_LENGTH - 1; i++) {
            token[i] = (char) ('0' + random.nextInt(10));
        }
        token[TOKEN_LENGTH - 1] = (char) ('0' + Luhn.checkDigit(CharBuffer.wrap(token, 0, TOKEN_LENGTH - 1)));
        return new String(token);
    }

    /** True if {@code candidate} has this format: 16 digits, this token BIN, valid Luhn. */
    public boolean isToken(CharSequence candidate) {
        return candidate.length() == TOKEN_LENGTH
                && startsWithTokenBin(candidate)
                && Luhn.isValid(candidate);
    }

    /** True if the first six characters equal the token BIN, whatever the length. */
    public boolean startsWithTokenBin(CharSequence candidate) {
        if (candidate.length() < tokenBin.length()) {
            return false;
        }
        for (int i = 0; i < tokenBin.length(); i++) {
            if (candidate.charAt(i) != tokenBin.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
