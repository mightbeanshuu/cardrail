package dev.cardrail.loadgen;

import java.util.Objects;

/**
 * A card the load generator sends authorizations for.
 *
 * @param pan    Luhn-valid, 12-19 digits
 * @param expiry YYMM, as field 14 carries it
 */
public record Card(String pan, String expiry) {

    public Card {
        Objects.requireNonNull(pan, "pan");
        Objects.requireNonNull(expiry, "expiry");
        if (pan.length() < 12 || pan.length() > 19 || !Luhn.isValid(pan)) {
            throw new IllegalArgumentException("PAN must be 12-19 digits and Luhn-valid");
        }
        if (!expiry.matches("\\d{2}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("expiry must be YYMM, got '" + expiry + "'");
        }
    }

    /** Never prints the PAN. */
    @Override
    public String toString() {
        return "Card[" + pan.substring(0, 6) + "..." + pan.substring(pan.length() - 4) + ", expiry=" + expiry + "]";
    }
}
