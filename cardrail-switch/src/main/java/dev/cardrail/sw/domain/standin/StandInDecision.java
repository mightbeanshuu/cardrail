package dev.cardrail.sw.domain.standin;

import dev.cardrail.sw.domain.authorization.ResponseCode;

public record StandInDecision(ResponseCode responseCode, String reason) {

    public boolean approved() {
        return responseCode.isApproved();
    }
}
