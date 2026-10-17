package dev.cardrail.sw.adapter.web;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Bean Validation here only rejects requests that cannot be read as an authorization at all (missing
 * fields, absurd sizes). Field formats are checked by the switch itself and answered with response
 * code 30, exactly as for an ISO 8583 request, so both entry points behave the same.
 */
record AuthorizationRequestBody(
        @NotBlank @Size(max = 32) String pan,
        @NotBlank @Size(max = 8) String expiry,
        @NotNull Long amountMinor,
        @NotNull Integer currency,
        @NotBlank @Size(max = 8) String mcc,
        @NotBlank @Size(max = 32) String merchantId,
        @NotBlank @Size(max = 64) String merchantName,
        @NotBlank @Size(max = 8) String merchantCountry,
        @NotBlank @Size(max = 16) String terminalId,
        @NotBlank @Size(max = 16) String acquirerId,
        @NotBlank @Size(max = 12) String stan,
        @NotBlank @Size(max = 24) String rrn,
        @NotBlank @Size(max = 16) String transmissionDateTime,
        @NotBlank @Size(max = 8) String posEntryMode) {

    AuthorizationRequest toDomain() {
        return new AuthorizationRequest(pan, expiry, amountMinor, currency, mcc, merchantId, merchantName,
                merchantCountry, terminalId, acquirerId, stan, rrn, transmissionDateTime, posEntryMode);
    }

    @Override
    public String toString() {
        return toDomain().toString();
    }
}
