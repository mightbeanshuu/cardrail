package dev.cardrail.vault;

/**
 * The input is not a usable card number. Messages describe the defect (length, Luhn) and never
 * echo the input, because the input is by definition something that looked like a PAN.
 */
public final class InvalidCardNumberException extends IllegalArgumentException {

    public InvalidCardNumberException(String message) {
        super(message);
    }
}
