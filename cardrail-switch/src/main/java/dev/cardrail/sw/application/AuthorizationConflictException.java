package dev.cardrail.sw.application;

/** The requested operation is not valid in the authorization's current state. */
public class AuthorizationConflictException extends RuntimeException {

    public AuthorizationConflictException(String message) {
        super(message);
    }
}
