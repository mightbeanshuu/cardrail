package dev.cardrail.vault;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of the vault, shaped for a relational table. Byte arrays are stored by reference;
 * stores must not mutate them after construction.
 *
 * @param token         the 16-digit surrogate; unique
 * @param blindIndex    HMAC-SHA256 of the PAN under the blind index key; unique
 * @param wrappedDek    see {@link Envelope#wrappedDek()}
 * @param dekKeyVersion id of the KEK currently wrapping the DEK; changes on rewrap
 * @param iv            PAN encryption nonce; never changes
 * @param ciphertext    PAN ciphertext plus tag; never changes
 * @param maskedPan     first 6 + last 4, for display and BIN routing without decryption
 * @param createdAt     when the token was issued
 */
public record VaultRecord(String token, byte[] blindIndex, byte[] wrappedDek, String dekKeyVersion, byte[] iv,
                          byte[] ciphertext, String maskedPan, Instant createdAt) {

    public VaultRecord {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(blindIndex, "blindIndex");
        Objects.requireNonNull(wrappedDek, "wrappedDek");
        Objects.requireNonNull(dekKeyVersion, "dekKeyVersion");
        Objects.requireNonNull(iv, "iv");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(maskedPan, "maskedPan");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public Envelope envelope() {
        return new Envelope(dekKeyVersion, wrappedDek, iv, ciphertext);
    }

    public VaultRecord withWrappedDek(String newKeyVersion, byte[] newWrappedDek) {
        return new VaultRecord(token, blindIndex, newWrappedDek, newKeyVersion, iv, ciphertext, maskedPan, createdAt);
    }

    @Override
    public String toString() {
        return "VaultRecord[token=" + token + ", maskedPan=" + maskedPan + ", dekKeyVersion=" + dekKeyVersion
                + ", createdAt=" + createdAt + "]";
    }
}
