package dev.cardrail.clearing.adapter.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * This service's copy of the switch's authorization event (docs/CONTRACTS.md section 1). It is
 * deliberately not shared code with the switch. Unknown fields are ignored so the switch can add
 * fields without a coordinated deploy; boxed types keep "absent" distinguishable from zero.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthorizationEvent(
        String eventType,
        UUID authorizationId,
        String status,
        String responseCode,
        String authCode,
        Boolean standIn,
        String standInTrigger,
        String reason,
        String token,
        String maskedPan,
        Long amountMinor,
        Integer currency,
        Long capturedAmountMinor,
        String mcc,
        String merchantId,
        String acquirerId,
        String terminalId,
        String stan,
        String rrn,
        Instant occurredAt) {
}
