package dev.cardrail.sw.port;

/** The message must never contain the PAN itself. */
public class InvalidCardNumberException extends RuntimeException {

    public InvalidCardNumberException(String message) {
        super(message);
    }
}
