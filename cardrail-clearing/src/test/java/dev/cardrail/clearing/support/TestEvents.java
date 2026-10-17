package dev.cardrail.clearing.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Switch events as the switch serializes them: a JSON object with null fields left out. */
public final class TestEvents {

    public static final String CAPTURED_TOPIC = "cardrail.authorization.captured";
    public static final String REVERSED_TOPIC = "cardrail.authorization.reversed";
    public static final String DEAD_LETTER_TOPIC = "cardrail.clearing.dlt";

    private TestEvents() {
    }

    public static Map<String, Object> captured(UUID authorizationId, long amountMinor, Instant occurredAt) {
        Map<String, Object> event = base("authorization.captured", "CAPTURED", authorizationId, occurredAt);
        event.put("amountMinor", amountMinor);
        event.put("capturedAmountMinor", amountMinor);
        return event;
    }

    public static Map<String, Object> reversed(UUID authorizationId, long amountMinor, Instant occurredAt) {
        Map<String, Object> event = base("authorization.reversed", "REVERSED", authorizationId, occurredAt);
        event.put("amountMinor", amountMinor);
        return event;
    }

    private static Map<String, Object> base(String type, String status, UUID id, Instant occurredAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", type);
        event.put("authorizationId", id.toString());
        event.put("status", status);
        event.put("responseCode", "00");
        event.put("authCode", "A1B2C3");
        event.put("standIn", false);
        event.put("token", "9999991234567890");
        event.put("maskedPan", "555555******4444");
        event.put("currency", 356);
        event.put("mcc", "5411");
        event.put("merchantId", "MERCHANT000001");
        event.put("acquirerId", "123456");
        event.put("terminalId", "TERM0001");
        event.put("stan", "000123");
        event.put("rrn", "629112000123");
        event.put("occurredAt", occurredAt.toString());
        return event;
    }
}
