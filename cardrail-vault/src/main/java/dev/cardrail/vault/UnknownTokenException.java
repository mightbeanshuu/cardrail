package dev.cardrail.vault;

/** No vault record for the supplied token. The input is not echoed: callers sometimes pass a PAN by mistake. */
public final class UnknownTokenException extends VaultException {

    UnknownTokenException(String message) {
        super(message);
    }
}
