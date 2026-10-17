package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A row to insert into the authorization store. Either {@code PENDING} with no response yet, or a
 * final decline decided before routing (format, card number). The request's PAN is never persisted.
 */
public record NewAuthorization(
        UUID id,
        AuthorizationRequest request,
        String payloadHash,
        TokenizedCard card,
        String maskedPan,
        AuthorizationStatus status,
        ResponseCode responseCode,
        String declineReason,
        Instant createdAt) {

    public NewAuthorization {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if ((status == AuthorizationStatus.PENDING) != (responseCode == null)) {
            throw new IllegalArgumentException("a response code is present exactly when not pending");
        }
        if (status != AuthorizationStatus.PENDING && status != AuthorizationStatus.DECLINED) {
            throw new IllegalArgumentException("new authorizations are PENDING or DECLINED, not " + status);
        }
    }

    public static NewAuthorization pending(UUID id, AuthorizationRequest request, String payloadHash,
                                           TokenizedCard card, Instant createdAt) {
        return new NewAuthorization(id, request, payloadHash, card, card.maskedPan(), AuthorizationStatus.PENDING,
                null, null, createdAt);
    }

    public static NewAuthorization rejected(UUID id, AuthorizationRequest request, String payloadHash,
                                            TokenizedCard card, String maskedPan, ResponseCode code,
                                            String reason, Instant createdAt) {
        return new NewAuthorization(id, request, payloadHash, card, maskedPan, AuthorizationStatus.DECLINED, code,
                reason, createdAt);
    }

    public String token() {
        return card == null ? null : card.token();
    }
}
