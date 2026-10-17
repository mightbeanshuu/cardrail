package dev.cardrail.sw.port;

import java.util.Objects;

/**
 * @param token     surrogate for the PAN; the same PAN always yields the same token
 * @param maskedPan first 6 + last 4 digits
 * @param bin       first 6 digits of the PAN
 * @param last4     last 4 digits of the PAN
 */
public record TokenizedCard(String token, String maskedPan, String bin, String last4) {

    public TokenizedCard {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(maskedPan, "maskedPan");
        Objects.requireNonNull(bin, "bin");
        Objects.requireNonNull(last4, "last4");
    }
}
