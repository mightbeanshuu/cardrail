package dev.cardrail.clearing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of clearing: a captured authorization, or the offset of one. Amounts are signed minor
 * units: a capture is positive, its offset is the exact negation (fee included), so the pair nets
 * to zero whatever the rate table says at the time of the reversal.
 */
public record ClearingRecord(
        Long id,
        UUID authorizationId,
        RecordType type,
        String token,
        String maskedPan,
        String issuerBin,
        String acquirerId,
        String merchantId,
        String mcc,
        int currency,
        long amountMinor,
        String interchangeCategory,
        long interchangeFeeMinor,
        LocalDate businessDate,
        Instant occurredAt,
        ClearingStatus status,
        LocalDate settlementDate,
        Instant settledAt,
        Instant createdAt) {

    public static final int BIN_LENGTH = 6;

    public static ClearingRecord capture(UUID authorizationId, String token, String maskedPan, String acquirerId,
                                         String merchantId, String mcc, int currency, long capturedAmountMinor,
                                         InterchangeRate rate, LocalDate businessDate, Instant occurredAt,
                                         Instant createdAt) {
        if (capturedAmountMinor <= 0) {
            throw new IllegalArgumentException("captured amount must be positive, was " + capturedAmountMinor);
        }
        return new ClearingRecord(null, authorizationId, RecordType.CAPTURE, token, maskedPan,
                maskedPan.substring(0, BIN_LENGTH), acquirerId, merchantId, mcc, currency, capturedAmountMinor,
                rate.category(), rate.feeFor(capturedAmountMinor), businessDate, occurredAt,
                ClearingStatus.PENDING_SETTLEMENT, null, null, createdAt);
    }

    public ClearingRecord offset(Instant reversedAt, Instant createdAt) {
        if (type != RecordType.CAPTURE) {
            throw new IllegalStateException("only a capture can be offset");
        }
        return new ClearingRecord(null, authorizationId, RecordType.REVERSAL, token, maskedPan, issuerBin,
                acquirerId, merchantId, mcc, currency, -amountMinor, interchangeCategory, -interchangeFeeMinor,
                businessDate, reversedAt, ClearingStatus.PENDING_SETTLEMENT, null, null, createdAt);
    }
}
