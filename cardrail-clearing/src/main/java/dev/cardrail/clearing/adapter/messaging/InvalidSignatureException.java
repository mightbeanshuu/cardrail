package dev.cardrail.clearing.adapter.messaging;

/** The request is not provably from the switch, or not recent enough to rule out a replay. */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}
