package dev.cardrail.vault;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * HMAC-SHA256 of the PAN under a dedicated key, stored alongside each record so the vault can find
 * an existing token for a PAN without decrypting anything. A keyed MAC rather than a plain hash
 * because the PAN space is small enough (about 10^9 candidates per BIN) to brute-force an unkeyed
 * SHA-256 from a database dump.
 */
public final class BlindIndex {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKey key;

    public BlindIndex(SecretKey key) {
        this.key = key;
    }

    /** 32-byte index value for {@code panDigits}. */
    public byte[] compute(char[] panDigits) {
        byte[] input = PanBytes.toAscii(panDigits);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }
}
