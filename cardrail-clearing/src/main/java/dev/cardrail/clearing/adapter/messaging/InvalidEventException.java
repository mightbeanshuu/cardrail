package dev.cardrail.clearing.adapter.messaging;

/** The message can never be processed as sent. Not retried: it goes straight to the dead-letter topic. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
