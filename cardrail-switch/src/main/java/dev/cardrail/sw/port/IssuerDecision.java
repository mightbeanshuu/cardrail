package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.authorization.ResponseCode;

import java.util.Objects;

/** @param authCode F38 from the issuer; present exactly when approved */
public record IssuerDecision(ResponseCode responseCode, String authCode) {

    public IssuerDecision {
        Objects.requireNonNull(responseCode, "responseCode");
        if (responseCode.isApproved() != (authCode != null)) {
            throw new IllegalArgumentException("an auth code is present exactly when approved");
        }
    }

    public static IssuerDecision approved(String authCode) {
        return new IssuerDecision(ResponseCode.APPROVED, authCode);
    }

    public static IssuerDecision declined(ResponseCode code) {
        return new IssuerDecision(code, null);
    }
}
