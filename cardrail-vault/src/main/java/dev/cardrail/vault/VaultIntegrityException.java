package dev.cardrail.vault;

/**
 * Stored card data failed authentication: ciphertext, IV, wrapped DEK or key id was altered,
 * truncated, or moved from another record. Treat as a security event, not a retryable error.
 */
public final class VaultIntegrityException extends VaultException {

    VaultIntegrityException(String message) {
        super(message);
    }

    VaultIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
