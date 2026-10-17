package dev.cardrail.vault;

/** The KEK a record needs is unknown to the {@link KeyProvider} or has been retired. */
public final class KeyUnavailableException extends VaultException {

    KeyUnavailableException(String message) {
        super(message);
    }
}
