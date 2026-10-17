package dev.cardrail.clearing.application;

import java.time.Instant;
import java.util.UUID;

public record ReversedAuthorization(UUID authorizationId, Instant occurredAt) {
}
