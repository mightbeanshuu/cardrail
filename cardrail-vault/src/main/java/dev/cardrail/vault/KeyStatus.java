package dev.cardrail.vault;

/** Lifecycle of a key-encryption key. */
public enum KeyStatus {
    /** Wraps new DEKs and unwraps existing ones. Exactly one KEK is active at a time. */
    ACTIVE,
    /** Unwraps only; records still under it are waiting for {@link TokenVault#rewrapAll()}. */
    DECRYPT_ONLY,
    /** Must not be used. A record still wrapped under a retired KEK cannot be detokenized. */
    RETIRED
}
