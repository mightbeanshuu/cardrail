package dev.cardrail.vault;

/** A vault operation could not complete. Messages never contain a PAN. */
public abstract sealed class VaultException extends RuntimeException
        permits VaultIntegrityException, KeyUnavailableException, UnknownTokenException {

    VaultException(String message) {
        super(message);
    }

    VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
