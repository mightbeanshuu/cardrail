package dev.cardrail.sw.adapter.web;

import dev.cardrail.sw.domain.authorization.StoredAuthorization;

import java.time.Instant;
import java.util.UUID;

record AuthorizationView(
        UUID id,
        String status,
        String responseCode,
        String authCode,
        boolean standIn,
        String declineReason,
        String maskedPan,
        String token,
        long amountMinor,
        int currency,
        String mcc,
        String merchantId,
        String merchantName,
        String acquirerId,
        String terminalId,
        String stan,
        String rrn,
        String transmissionDateTime,
        Long capturedAmountMinor,
        Instant capturedAt,
        Instant reversedAt,
        Instant expiredAt,
        Instant createdAt) {

    static AuthorizationView of(StoredAuthorization a) {
        return new AuthorizationView(a.id(), a.status().name(),
                a.responseCode() == null ? null : a.responseCode().code(), a.authCode(), a.standIn(),
                a.declineReason(), a.maskedPan(), a.token(), a.amountMinor(), a.currency(), a.mcc(), a.merchantId(),
                a.merchantName(), a.key().acquirerId(), a.key().terminalId(), a.key().stan(), a.rrn(),
                a.key().transmissionDateTime(), a.capturedAmountMinor(), a.capturedAt(), a.reversedAt(),
                a.expiredAt(), a.createdAt());
    }
}
