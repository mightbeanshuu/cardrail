package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.DuplicateDecision;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.TokenizedCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Step (c), the losing side: the insert found the transmission key taken. Detection itself is the
 * unique constraint (ADR 0003); this class only decides what to answer.
 */
@Component
public class DuplicateResolver {

    private static final Logger log = LoggerFactory.getLogger(DuplicateResolver.class);

    private final AuthorizationRepository authorizations;
    private final DuplicateProperties properties;
    private final SwitchMetrics metrics;
    private final Clock clock;

    public DuplicateResolver(AuthorizationRepository authorizations, DuplicateProperties properties,
                             SwitchMetrics metrics, Clock clock) {
        this.authorizations = authorizations;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @param card null when the PAN could not be tokenized */
    public AuthorizationResult resolve(AuthorizationRequest request, String payloadHash, TokenizedCard card) {
        Instant deadline = clock.instant().plus(properties.inFlightWait());
        while (true) {
            StoredAuthorization existing = authorizations.findByKey(request.transmissionKey())
                    .orElseThrow(() -> new IllegalStateException("transmission key conflicted but no row exists"));
            switch (DuplicateDecision.of(existing, payloadHash)) {
                case DuplicateDecision.Replay replay -> {
                    metrics.duplicate("replay");
                    log.info("retransmission of authorization {}, replaying response {}",
                            existing.id(), replay.original().responseCode().code());
                    return replay.original();
                }
                case DuplicateDecision.Mismatch ignored -> {
                    metrics.duplicate("mismatch");
                    log.warn("transmission key of authorization {} reused with different content", existing.id());
                    return duplicateTransmission(card);
                }
                case DuplicateDecision.InFlight ignored -> {
                    if (!clock.instant().isBefore(deadline) || !pause()) {
                        metrics.duplicate("in_flight");
                        return duplicateTransmission(card);
                    }
                }
            }
        }
    }

    private boolean pause() {
        try {
            Thread.sleep(properties.pollInterval());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static AuthorizationResult duplicateTransmission(TokenizedCard card) {
        return new AuthorizationResult(null, ResponseCode.DUPLICATE_TRANSMISSION, null, false,
                card == null ? null : card.maskedPan(), card == null ? null : card.token());
    }
}
