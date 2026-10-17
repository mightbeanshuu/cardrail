package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
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
 * Completion: settles all or part of a hold to the merchant's settlement account and releases the
 * rest. One capture per authorization; repeating it with the same amount returns the same result.
 */
@Service
public class CaptureService {

    private final AuthorizationRepository authorizations;
    private final IssuerLedger ledger;
    private final OutboxRepository outbox;
    private final AuditTrail audit;
    private final AuthorizationEvents events;
    private final Clock clock;

    public CaptureService(AuthorizationRepository authorizations, IssuerLedger ledger, OutboxRepository outbox,
                          AuditTrail audit, AuthorizationEvents events, Clock clock) {
        this.authorizations = authorizations;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** @param amountMinor null captures the full authorized amount */
    @Transactional
    public CaptureResult capture(UUID authorizationId, Long amountMinor, String actor) {
        StoredAuthorization a = authorizations.lockById(authorizationId)
                .orElseThrow(() -> new AuthorizationNotFoundException(authorizationId.toString()));
        long amount = amountMinor == null ? a.amountMinor() : amountMinor;
        if (a.status() == AuthorizationStatus.CAPTURED) {
            if (a.capturedAmountMinor() == amount) {
                return new CaptureResult(a.id(), amount, a.amountMinor() - amount, a.capturedAt());
            }
            throw new AuthorizationConflictException("authorization " + a.id() + " was already captured for "
                    + a.capturedAmountMinor());
        }
        if (a.status() != AuthorizationStatus.AUTHORIZED) {
            throw new AuthorizationConflictException("authorization " + a.id() + " is " + a.status()
                    + " and cannot be captured");
        }
        if (a.standIn()) {
            throw new AuthorizationConflictException("authorization " + a.id() + " was approved in stand-in and "
                    + "holds no funds; it is settled when the issuer posts the stand-in advice");
        }
        if (amount <= 0 || amount > a.amountMinor()) {
            throw new InvalidCaptureAmountException(amount, a.amountMinor());
        }
        Instant now = clock.instant();
        ledger.captureHold(a.id(), amount, settlementAccountRef(a));
        StoredAuthorization captured = authorizations.markCaptured(a.id(), amount, now);
        outbox.append(events.stateChanged(AuthorizationEvents.CAPTURED, captured, now));
        audit.append(AuditEntry.of(actor, "AUTHORIZATION_CAPTURED", "authorization", a.id().toString(),
                Map.of("capturedMinor", Long.toString(amount),
                        "releasedMinor", Long.toString(a.amountMinor() - amount),
                        "currency", Integer.toString(a.currency()))));
        return new CaptureResult(a.id(), amount, a.amountMinor() - amount, now);
    }

    private static String settlementAccountRef(StoredAuthorization a) {
        return "settlement-payable:" + a.key().acquirerId() + ":" + a.merchantId() + ":" + a.currency();
    }
}
