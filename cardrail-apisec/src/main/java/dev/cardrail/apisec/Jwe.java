package dev.cardrail.apisec;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cardrail.apisec.JweException.Reason;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JWE compact serialization (RFC 7516 §7.1) restricted to one algorithm pair: key management
 * {@code RSA-OAEP-256} and content encryption {@code A256GCM} (RFC 7518 §4.3, §5.3). This is the only
 * pair Mastercard's {@code JweEncryption} emits. Any other {@code alg} or {@code enc} is rejected before a
 * key is touched, so a sender cannot steer the decryptor into a different algorithm.
 */
public final class Jwe {

    public static final String ALG = "RSA-OAEP-256";
    public static final String ENC = "A256GCM";

    /** RFC 7518 §5.3 requires a 96-bit IV for AES-GCM; this module always generates one. */
    public static final int RFC_IV_LENGTH = 12;

    /**
     * Mastercard's client-encryption 1.8.9 generates a 16-byte GCM IV unless configured with
     * {@code withEncryptionIVSize(12)}. GCM accepts it (the IV is GHASHed into J0, NIST SP 800-38D §7.1),
     * so accepting both lengths is required for interop with that library's default.
     */
    public static final Set<Integer> MASTERCARD_COMPATIBLE_IV_LENGTHS = Set.of(12, 16);

    private static final int CEK_LENGTH = 32;
    private static final int TAG_BITS = 128;
    private static final int TAG_LENGTH = TAG_BITS / 8;
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]*");

    /*
     * OAEP parameters must be passed explicitly. "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" without an
     * OAEPParameterSpec makes SunJCE use SHA-256 for the label hash but SHA-1 for MGF1, which is not
     * RSA-OAEP-256 (RFC 7518 §4.3 requires MGF1 with SHA-256). Keys wrapped that way fail to unwrap in
     * every conforming JOSE implementation, and vice versa: a classic JCA interop bug.
     */
    private static final OAEPParameterSpec RSA_OAEP_256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static final JsonMapper HEADER_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final SecureRandom RANDOM = new SecureRandom();

    private Jwe() {
    }

    /**
     * @param contentType value for the {@code cty} header, or {@code null} to omit it
     */
    public static String encrypt(byte[] plaintext, RSAPublicKey recipient, String kid, String contentType) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(kid, "kid");

        ObjectNode header = HEADER_MAPPER.createObjectNode();
        header.put("alg", ALG);
        header.put("enc", ENC);
        header.put("kid", kid);
        if (contentType != null) {
            header.put("cty", contentType);
        }
        String encodedHeader;
        try {
            encodedHeader = base64Url(HEADER_MAPPER.writeValueAsBytes(header));
        } catch (JacksonException e) {
            throw new IllegalStateException("serialising a flat string map cannot fail", e);
        }

        byte[] cek = new byte[CEK_LENGTH];
        byte[] iv = new byte[RFC_IV_LENGTH];
        RANDOM.nextBytes(cek);
        RANDOM.nextBytes(iv);
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, recipient, RSA_OAEP_256);
            byte[] encryptedKey = rsa.doFinal(cek);

            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            // RFC 7516 §5.1 step 14: AAD = ASCII(BASE64URL(UTF8(JWE Protected Header))).
            gcm.updateAAD(encodedHeader.getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = gcm.doFinal(plaintext);
            int ctLength = sealed.length - TAG_LENGTH;
            return encodedHeader
                    + '.' + base64Url(encryptedKey)
                    + '.' + base64Url(iv)
                    + '.' + base64Url(Arrays.copyOfRange(sealed, 0, ctLength))
                    + '.' + base64Url(Arrays.copyOfRange(sealed, ctLength, sealed.length));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWE encryption failed", e);
        } finally {
            Arrays.fill(cek, (byte) 0);
        }
    }

    /**
     * @param keysByKid           decryption keys indexed by the {@code kid} they are published under
     * @param acceptedIvLengths   IV lengths in bytes to accept; see {@link #MASTERCARD_COMPATIBLE_IV_LENGTHS}
     * @throws JweException on any failure; the reason never distinguishes a bad wrapped key from a bad tag
     */
    public static byte[] decrypt(String compact, Map<String, RSAPrivateKey> keysByKid, Set<Integer> acceptedIvLengths) {
        Objects.requireNonNull(compact, "compact");
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 5) {
            throw new JweException(Reason.MALFORMED, "JWE compact serialization has " + parts.length + " parts, expected 5");
        }

        JsonNode header = parseHeader(parts[0]);
        JsonNode alg = header.get("alg");
        JsonNode enc = header.get("enc");
        if (alg == null || !alg.isTextual() || !ALG.equals(alg.textValue())
                || enc == null || !enc.isTextual() || !ENC.equals(enc.textValue())) {
            throw new JweException(Reason.UNSUPPORTED_ALGORITHM, "only alg=" + ALG + ", enc=" + ENC + " are accepted");
        }
        if (header.has("zip") || header.has("crit")) {
            throw new JweException(Reason.UNSUPPORTED_ALGORITHM, "zip and crit headers are not supported");
        }
        JsonNode kid = header.get("kid");
        if (kid == null || !kid.isTextual()) {
            throw new JweException(Reason.MALFORMED, "kid header is required");
        }
        RSAPrivateKey key = keysByKid.get(kid.textValue());
        if (key == null) {
            throw new JweException(Reason.UNKNOWN_KEY, "no decryption key for kid");
        }

        byte[] encryptedKey = base64UrlDecode(parts[1], "encrypted key");
        byte[] iv = base64UrlDecode(parts[2], "IV");
        byte[] ciphertext = base64UrlDecode(parts[3], "ciphertext");
        byte[] tag = base64UrlDecode(parts[4], "authentication tag");
        if (!acceptedIvLengths.contains(iv.length)) {
            throw new JweException(Reason.MALFORMED, "IV length " + iv.length + " not accepted");
        }
        if (tag.length != TAG_LENGTH) {
            throw new JweException(Reason.MALFORMED, "authentication tag must be " + TAG_LENGTH + " bytes");
        }

        byte[] cek = unwrapOrRandom(key, encryptedKey);
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            gcm.updateAAD(parts[0].getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = Arrays.copyOf(ciphertext, ciphertext.length + TAG_LENGTH);
            System.arraycopy(tag, 0, sealed, ciphertext.length, TAG_LENGTH);
            return gcm.doFinal(sealed);
        } catch (AEADBadTagException e) {
            throw new JweException(Reason.DECRYPTION_FAILED, "JWE decryption failed");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable or misconfigured", e);
        } finally {
            Arrays.fill(cek, (byte) 0);
        }
    }

    /** Parsed protected header, for callers that need {@code kid} or {@code cty} before decrypting. */
    static JsonNode parseHeader(String encodedHeader) {
        byte[] json = base64UrlDecode(encodedHeader, "protected header");
        JsonNode header;
        try {
            header = HEADER_MAPPER.readTree(json);
        } catch (IOException e) {
            throw new JweException(Reason.MALFORMED, "protected header is not valid JSON");
        }
        if (header == null || !header.isObject()) {
            throw new JweException(Reason.MALFORMED, "protected header must be a JSON object");
        }
        return header;
    }

    /*
     * RFC 7516 §11.5: if RSA unwrap fails, continue with a random CEK so the failure surfaces as the same GCM
     * tag error as a tampered ciphertext. This removes the error-type oracle (Bleichenbacher/Manger style); it
     * does not make the RSA operation itself constant-time.
     */
    private static byte[] unwrapOrRandom(RSAPrivateKey key, byte[] encryptedKey) {
        byte[] cek;
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.DECRYPT_MODE, key, RSA_OAEP_256);
            cek = rsa.doFinal(encryptedKey);
        } catch (GeneralSecurityException e) {
            cek = null;
        }
        if (cek == null || cek.length != CEK_LENGTH) {
            cek = new byte[CEK_LENGTH];
            RANDOM.nextBytes(cek);
        }
        return cek;
    }

    static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** Strict base64url: URL alphabet, no padding, canonical trailing bits (so each value has one encoding). */
    static byte[] base64UrlDecode(String part, String what) {
        if (!BASE64URL.matcher(part).matches() || part.length() % 4 == 1) {
            throw new JweException(Reason.MALFORMED, what + " is not unpadded base64url");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(part);
        if (!base64Url(decoded).equals(part)) {
            throw new JweException(Reason.MALFORMED, what + " is not canonical base64url");
        }
        return decoded;
    }
}
