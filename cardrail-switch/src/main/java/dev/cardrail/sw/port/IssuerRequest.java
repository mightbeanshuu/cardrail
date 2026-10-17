package dev.cardrail.sw.port;

import java.util.Objects;
import java.util.UUID;

/**
 * @param authorizationId idempotency key: the issuer must treat repeats of the same id as one request
 */
public record IssuerRequest(UUID authorizationId, String token, long amountMinor, int currency, String mcc,
                            String merchantId) {

    public IssuerRequest {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(mcc, "mcc");
        Objects.requireNonNull(merchantId, "merchantId");
    }
}
