package dev.cardrail.sw.domain.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * The switch's answer to an authorization request (what goes back in the 0110).
 *
 * @param id        the stored authorization; null when nothing was stored (a 94 for a key already taken)
 * @param authCode  F38, present only on approval
 * @param maskedPan null when the PAN could not be tokenized
 * @param token     null when the PAN could not be tokenized
 */
public record AuthorizationResult(
        UUID id,
        ResponseCode responseCode,
        String authCode,
        boolean standIn,
        String maskedPan,
        String token) {

    public AuthorizationResult {
        Objects.requireNonNull(responseCode, "responseCode");
        if (responseCode.isApproved() != (authCode != null)) {
            throw new IllegalArgumentException("an auth code is present exactly when approved");
        }
    }

    public boolean approved() {
        return responseCode.isApproved();
    }
}
