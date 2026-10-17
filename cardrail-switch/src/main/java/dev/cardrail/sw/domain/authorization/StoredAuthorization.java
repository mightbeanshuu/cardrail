package dev.cardrail.sw.domain.authorization;

import java.time.Instant;
import java.util.UUID;

/** An authorization as persisted. Response fields are null while {@link AuthorizationStatus#PENDING}. */
public record StoredAuthorization(
        UUID id,
        TransmissionKey key,
        String rrn,
        String payloadHash,
        String token,
        String maskedPan,
        long amountMinor,
        int currency,
        String mcc,
        String merchantId,
        String merchantName,
        AuthorizationStatus status,
        ResponseCode responseCode,
        String authCode,
        boolean standIn,
        String declineReason,
        Long capturedAmountMinor,
        Instant capturedAt,
        Instant reversedAt,
        Instant expiredAt,
        Instant createdAt) {

    public AuthorizationResult toResult() {
        if (status == AuthorizationStatus.PENDING) {
            throw new IllegalStateException("authorization " + id + " has no response yet");
        }
        return new AuthorizationResult(id, responseCode, authCode, standIn, maskedPan, token);
    }
}
