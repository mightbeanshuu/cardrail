package dev.cardrail.sw.application.risk;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.port.TokenizedCard;

import java.time.Instant;
import java.util.UUID;

public record RiskContext(UUID authorizationId, AuthorizationRequest request, TokenizedCard card, Instant now) {
}
