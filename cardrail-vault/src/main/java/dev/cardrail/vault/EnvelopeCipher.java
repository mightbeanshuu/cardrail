package dev.cardrail.vault;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption of a PAN.
 *
 * <ul>
 *   <li>Each record gets its own random 256-bit DEK. The PAN is encrypted under it with
 *       AES-256-GCM, a random 96-bit IV and a 128-bit tag; AAD binds the ciphertext to the token.</li>
 *   <li>The DEK is wrapped with AES-256-GCM under the active KEK; AAD binds the wrapped DEK to the
 *       token and the KEK id.</li>
 * </ul>
 *
 * <p>The KEK id is in the wrap AAD rather than the PAN AAD on purpose: rotation rewraps the DEK
 * under a new KEK and must leave the PAN ciphertext byte-for-byte untouched, so the PAN's AAD can
 * only contain values that never change for the life of the record.
 *
 * <p>Every authentication failure surfaces as {@link VaultIntegrityException}.
 */
public final class EnvelopeCipher {

    static final int IV_BYTES = 12;
    static final int TAG_BYTES = 16;
    static final int DEK_BYTES = 32;
    static final int WRAPPED_DEK_BYTES = IV_BYTES + DEK_BYTES + TAG_BYTES;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PAN_AAD_LABEL = "cardrail-vault/pan/v1";
    private static final String DEK_AAD_LABEL = "cardrail-vault/dek/v1";

    private final KeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeCipher(KeyProvider keys) {
        this.keys = keys;
    }

    /** Encrypts {@code panDigits} for the record identified by {@code token}. Does not retain or wipe the input. */
    public Envelope seal(String token, char[] panDigits) {
        KeyVersion kek = keys.activeKek();
        if (kek.status() != KeyStatus.ACTIVE) {
            throw new KeyUnavailableException("KeyProvider returned " + kek + " as active");
        }
        SecretKey dek = newDek();
        byte[] iv = randomIv();
        byte[] plaintext = PanBytes.toAscii(panDigits);
        try {
            byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dek, iv, panAad(token)).doFinal(plaintext);
            return new Envelope(kek.id(), wrap(dek, kek, token), iv, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Decrypts the PAN digits. The caller owns the returned array and should wipe it. */
    public char[] open(String token, Envelope envelope) {
        if (envelope.iv().length != IV_BYTES || envelope.ciphertext().length <= TAG_BYTES) {
            throw new VaultIntegrityException("malformed PAN ciphertext for token " + token);
        }
        SecretKey dek = unwrap(token, envelope);
        byte[] plaintext;
        try {
            plaintext = gcm(Cipher.DECRYPT_MODE, dek, envelope.iv(), panAad(token)).doFinal(envelope.ciphertext());
        } catch (AEADBadTagException e) {
            throw new VaultIntegrityException("PAN ciphertext failed authentication for token " + token, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
        try {
            return PanBytes.fromAscii(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Re-wraps the DEK under the current active KEK. IV and PAN ciphertext are returned unchanged. */
    public Envelope rewrap(String token, Envelope envelope) {
        KeyVersion target = keys.activeKek();
        SecretKey dek = unwrap(token, envelope);
        return new Envelope(target.id(), wrap(dek, target, token), envelope.iv(), envelope.ciphertext());
    }

    private byte[] wrap(SecretKey dek, KeyVersion kek, String token) {
        byte[] iv = randomIv();
        byte[] raw = dek.getEncoded();
        try {
            byte[] sealed = gcm(Cipher.ENCRYPT_MODE, kek.key(), iv, dekAad(token, kek.id())).doFinal(raw);
            byte[] wrapped = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, wrapped, 0, IV_BYTES);
            System.arraycopy(sealed, 0, wrapped, IV_BYTES, sealed.length);
            return wrapped;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM key wrap failed", e);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private SecretKey unwrap(String token, Envelope envelope) {
        KeyVersion kek = keys.kek(envelope.kekId()).orElseThrow(() -> new KeyUnavailableException(
                "no KEK with id " + envelope.kekId() + " (needed by token " + token + ")"));
        if (kek.status() == KeyStatus.RETIRED) {
            throw new KeyUnavailableException("KEK " + kek.id() + " is RETIRED but still wraps the DEK for token "
                    + token + "; run rewrapAll() before retiring a KEK");
        }
        byte[] wrapped = envelope.wrappedDek();
        if (wrapped.length != WRAPPED_DEK_BYTES) {
            throw new VaultIntegrityException("malformed wrapped DEK for token " + token);
        }
        byte[] raw;
        try {
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BYTES * 8, wrapped, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, kek.key(), spec);
            cipher.updateAAD(dekAad(token, kek.id()));
            raw = cipher.doFinal(wrapped, IV_BYTES, wrapped.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new VaultIntegrityException("wrapped DEK failed authentication for token " + token, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM key unwrap failed", e);
        }
        try {
            return new SecretKeySpec(raw, "AES");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static Cipher gcm(int mode, SecretKey key, byte[] iv, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
        cipher.updateAAD(aad);
        return cipher;
    }

    private SecretKey newDek() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(DEK_BYTES * 8, random);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES key generation unavailable", e);
        }
    }

    private byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        return iv;
    }

    private static byte[] panAad(String token) {
        return aad(PAN_AAD_LABEL, token);
    }

    private static byte[] dekAad(String token, String kekId) {
        return aad(DEK_AAD_LABEL, token, kekId);
    }

    // Length-prefixed so that ("ab", "c") and ("a", "bc") cannot produce the same AAD.
    private static byte[] aad(String... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (String part : parts) {
                out.writeUTF(part);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
