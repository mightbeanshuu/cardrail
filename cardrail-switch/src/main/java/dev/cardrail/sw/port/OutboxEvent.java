package dev.cardrail.sw.port;

import java.util.Objects;

/** @param messageKey Kafka record key; events for one card share a key and so stay in order */
public record OutboxEvent(String aggregateType, String aggregateId, String eventType, String messageKey,
                          String payloadJson) {

    public OutboxEvent {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }
}
