package dev.cardrail.sw.application;

/** The authorization is still PENDING; the same operation can succeed once it has been decided. */
public class AuthorizationInFlightException extends AuthorizationConflictException {

    public AuthorizationInFlightException(String message) {
        super(message);
    }
}
