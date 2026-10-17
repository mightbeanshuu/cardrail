package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.domain.authorization.TransmissionKey;
import dev.cardrail.sw.domain.ledger.JournalKind;
import dev.cardrail.sw.port.AuditTrail;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.port.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 0400 reversal. The authorization row lock serializes concurrent reversals of the same
 * authorization, and the status check after acquiring it makes the second one a no-op.
 */
@Service
public class ReverseService {

    private final AuthorizationRepository authorizations;
    private final IssuerLedger ledger;
    private final OutboxRepository outbox;
    private final AuditTrail audit;
    private final AuthorizationEvents events;
    private final Clock clock;

    public ReverseService(AuthorizationRepository authorizations, IssuerLedger ledger, OutboxRepository outbox,
                          AuditTrail audit, AuthorizationEvents events, Clock clock) {
        this.authorizations = authorizations;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** Locates the original by its data elements (F90 plus the terminal), as a 0400 from the acquirer does. */
    @Transactional
    public ReversalResult reverse(TransmissionKey original, String actor) {
        UUID id = authorizations.findByKey(original)
                .map(StoredAuthorization::id)
                .orElseThrow(() -> new AuthorizationNotFoundException("for the original data elements"));
        return reverse(id, actor);
    }

    @Transactional
    public ReversalResult reverse(UUID authorizationId, String actor) {
        StoredAuthorization a = authorizations.lockById(authorizationId)
                .orElseThrow(() -> new AuthorizationNotFoundException(authorizationId.toString()));
        return switch (a.status()) {
            case AUTHORIZED -> release(a, actor);
            case REVERSED -> new ReversalResult(a.id(), ReversalResult.Outcome.REVERSED, a.status(), heldAmount(a),
                    a.reversedAt());
            case DECLINED, EXPIRED -> new ReversalResult(a.id(), ReversalResult.Outcome.NOTHING_TO_REVERSE,
                    a.status(), 0, null);
            case CAPTURED -> throw new AuthorizationConflictException(
                    "authorization " + a.id() + " is captured; reverse it with a refund, not a reversal");
            case PENDING -> throw new AuthorizationInFlightException(
                    "authorization " + a.id() + " is still being decided; retry the reversal");
        };
    }

    private ReversalResult release(StoredAuthorization a, String actor) {
        Instant now = clock.instant();
        long released = ledger.releaseHold(a.id(), JournalKind.HOLD_RELEASE);
        StoredAuthorization reversed = authorizations.markReversed(a.id(), now);
        outbox.append(events.stateChanged(AuthorizationEvents.REVERSED, reversed, now));
        audit.append(AuditEntry.of(actor, "AUTHORIZATION_REVERSED", "authorization", a.id().toString(),
                Map.of("releasedMinor", Long.toString(released), "currency", Integer.toString(a.currency()))));
        return new ReversalResult(a.id(), ReversalResult.Outcome.REVERSED, reversed.status(), released, now);
    }

    private static long heldAmount(StoredAuthorization a) {
        return a.standIn() ? 0 : a.amountMinor();
    }
}
