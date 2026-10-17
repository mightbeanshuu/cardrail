package dev.cardrail.sw.application;

public class AuthorizationNotFoundException extends RuntimeException {

    public AuthorizationNotFoundException(String what) {
        super("no authorization " + what);
    }
}
