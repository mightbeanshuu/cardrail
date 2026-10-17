package dev.cardrail.sw.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.sw.application.issuer.IssuerOutcome;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.OutboxEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Builds the outbox events for authorization state changes. */
@Component
public class AuthorizationEvents {

    public static final String APPROVED = "authorization.approved";
    public static final String DECLINED = "authorization.declined";
    public static final String STIP_ADVICE = "stip.advice";
    public static final String REVERSED = "authorization.reversed";
    public static final String CAPTURED = "authorization.captured";
    public static final String EXPIRED = "authorization.expired";

    private static final String AGGREGATE_TYPE = "authorization";

    private final ObjectMapper json;

    public AuthorizationEvents(ObjectMapper json) {
        this.json = json;
    }

    public OutboxEvent decided(StoredAuthorization a, Instant at) {
        return event(a.responseCode().isApproved() ? APPROVED : DECLINED, a, null, at);
    }

    /** Tells the issuer what the switch decided on its behalf, so the issuer can post it later. */
    public OutboxEvent standInAdvice(StoredAuthorization a, IssuerOutcome.Trigger trigger, Instant at) {
        return event(STIP_ADVICE, a, trigger, at);
    }

    public OutboxEvent stateChanged(String eventType, StoredAuthorization a, Instant at) {
        return event(eventType, a, null, at);
    }

    private OutboxEvent event(String type, StoredAuthorization a, IssuerOutcome.Trigger trigger, Instant at) {
        Payload payload = new Payload(type, a.id(), a.status().name(), a.responseCode().code(), a.authCode(),
                a.standIn(), trigger == null ? null : trigger.name(), a.declineReason(), a.token(), a.maskedPan(),
                a.amountMinor(), a.currency(), a.capturedAmountMinor(), a.mcc(), a.merchantId(),
                a.key().acquirerId(), a.key().terminalId(), a.key().stan(), a.rrn(), at);
        String key = a.token() != null ? a.token() : a.id().toString();
        return new OutboxEvent(AGGREGATE_TYPE, a.id().toString(), type, key, toJson(payload));
    }

    private String toJson(Payload payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload is a plain record and always serializes", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Payload(
            String eventType,
            UUID authorizationId,
            String status,
            String responseCode,
            String authCode,
            boolean standIn,
            String standInTrigger,
            String reason,
            String token,
            String maskedPan,
            long amountMinor,
            int currency,
            Long capturedAmountMinor,
            String mcc,
            String merchantId,
            String acquirerId,
            String terminalId,
            String stan,
            String rrn,
            Instant occurredAt) {
    }
}
