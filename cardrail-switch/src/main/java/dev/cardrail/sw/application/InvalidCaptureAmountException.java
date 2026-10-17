package dev.cardrail.sw.application;

public class InvalidCaptureAmountException extends RuntimeException {

    public InvalidCaptureAmountException(long requested, long authorized) {
        super("capture amount " + requested + " must be between 1 and the authorized " + authorized);
    }
}
