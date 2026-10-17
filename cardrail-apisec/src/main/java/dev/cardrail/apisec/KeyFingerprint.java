package dev.cardrail.apisec;

import java.security.PublicKey;
import java.util.HexFormat;

/**
 * The key identifier Mastercard's client-encryption library puts in the JWE {@code kid} header when none is
 * configured: lower-case hex SHA-256 of the public key's DER {@code SubjectPublicKeyInfo} encoding
 * ({@code PublicKey.getEncoded()}), not of a certificate.
 */
public final class KeyFingerprint {

    private KeyFingerprint() {
    }

    public static String sha256Hex(PublicKey key) {
        if (!"X.509".equals(key.getFormat())) {
            throw new IllegalArgumentException("expected an X.509 SubjectPublicKeyInfo encoded key");
        }
        return HexFormat.of().formatHex(OAuth1Verifier.sha256(key.getEncoded()));
    }
}
