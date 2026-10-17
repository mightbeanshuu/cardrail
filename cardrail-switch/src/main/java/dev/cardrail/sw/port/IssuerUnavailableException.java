package dev.cardrail.sw.port;

public class IssuerUnavailableException extends RuntimeException {

    public IssuerUnavailableException(String message) {
        super(message);
    }

    public IssuerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
