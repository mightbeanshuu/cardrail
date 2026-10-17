package dev.cardrail.sw.port;

public record OutboxMessage(long id, String eventType, String messageKey, String payloadJson, int attempts) {
}
