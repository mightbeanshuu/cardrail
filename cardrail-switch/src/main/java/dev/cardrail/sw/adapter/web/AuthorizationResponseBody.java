package dev.cardrail.sw.adapter.web;

import dev.cardrail.sw.domain.authorization.AuthorizationResult;

import java.util.UUID;

/** @param responseCodeName readable name of {@code responseCode}, for people reading the JSON */
record AuthorizationResponseBody(UUID id, boolean approved, String responseCode, String responseCodeName,
                                 String authCode, boolean standIn, String maskedPan, String token) {

    static AuthorizationResponseBody of(AuthorizationResult r) {
        return new AuthorizationResponseBody(r.id(), r.approved(), r.responseCode().code(), r.responseCode().name(),
                r.authCode(), r.standIn(), r.maskedPan(), r.token());
    }
}
