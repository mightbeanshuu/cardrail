package dev.cardrail.vault;

import java.util.Objects;

/**
 * The encrypted parts of one vault record.
 *
 * @param kekId      id of the KEK that wraps the DEK
 * @param wrappedDek 12-byte wrap nonce followed by the AES-GCM encryption of the DEK and its tag
 * @param iv         12-byte nonce used to encrypt the PAN under the DEK
 * @param ciphertext AES-GCM encryption of the PAN's ASCII digits followed by the 16-byte tag
 */
public record Envelope(String kekId, byte[] wrappedDek, byte[] iv, byte[] ciphertext) {

    public Envelope {
        Objects.requireNonNull(kekId, "kekId");
        Objects.requireNonNull(wrappedDek, "wrappedDek");
        Objects.requireNonNull(iv, "iv");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }
}
