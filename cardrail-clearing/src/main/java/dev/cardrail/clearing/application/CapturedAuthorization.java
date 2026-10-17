package dev.cardrail.clearing.application;

import java.time.Instant;
import java.util.UUID;

public record CapturedAuthorization(UUID authorizationId, String token, String maskedPan, String acquirerId,
                                    String merchantId, String mcc, int currency, long capturedAmountMinor,
                                    Instant occurredAt) {
}
