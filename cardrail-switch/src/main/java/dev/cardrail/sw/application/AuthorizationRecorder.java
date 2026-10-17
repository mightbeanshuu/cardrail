package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.AuditTrail;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.port.NewAuthorization;
import dev.cardrail.sw.port.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Step (g): the decision, its outbox events and its audit record are written in one transaction, so
 * none of them can exist without the others.
 */
@Component
public class AuthorizationRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationRecorder.class);

    private final AuthorizationRepository authorizations;
    private final IssuerLedger ledger;
    private final OutboxRepository outbox;
    private final AuditTrail audit;
    private final AuthorizationEvents events;
    private final SwitchMetrics metrics;
    private final Clock clock;

    public AuthorizationRecorder(AuthorizationRepository authorizations, IssuerLedger ledger, OutboxRepository outbox,
                                 AuditTrail audit, AuthorizationEvents events, SwitchMetrics metrics, Clock clock) {
        this.authorizations = authorizations;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Stores a decline decided before routing. Empty if the transmission key is already taken. */
    @Transactional
    public Optional<AuthorizationResult> recordRejection(NewAuthorization rejected, String actor) {
        return authorizations.insertIfAbsent(rejected).map(stored -> {
            outbox.append(events.decided(stored, rejected.createdAt()));
            audit.append(auditEntry(actor, stored, "rejected-before-routing"));
            return stored.toResult();
        });
    }

    @Transactional
    public AuthorizationResult recordDecision(UUID authorizationId, Decision decision, String actor) {
        Instant now = clock.instant();
        if (decision.issuerAnswerUnknown() && ledger.voidHold(authorizationId)) {
            metrics.lateIssuerHoldReleased();
            log.warn("released a hold the issuer placed after the switch stopped waiting for it");
        }
        AuthorizationStatus status = decision.responseCode().isApproved()
                ? AuthorizationStatus.AUTHORIZED
                : AuthorizationStatus.DECLINED;
        StoredAuthorization stored = authorizations.recordDecision(authorizationId, status, decision.responseCode(),
                decision.authCode(), decision.standIn(), decision.responseCode().isApproved() ? null : decision.reason(),
                now);
        outbox.append(events.decided(stored, now));
        if (decision.standIn()) {
            outbox.append(events.standInAdvice(stored, decision.standInTrigger(), now));
        }
        audit.append(auditEntry(actor, stored, decision.source().name().toLowerCase(Locale.ROOT)));
        return stored.toResult();
    }

    private static AuditEntry auditEntry(String actor, StoredAuthorization a, String decidedBy) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("status", a.status().name());
        details.put("responseCode", a.responseCode().code());
        details.put("decidedBy", decidedBy);
        details.put("standIn", Boolean.toString(a.standIn()));
        details.put("amountMinor", Long.toString(a.amountMinor()));
        details.put("currency", Integer.toString(a.currency()));
        if (a.maskedPan() != null) {
            details.put("maskedPan", a.maskedPan());
        }
        if (a.declineReason() != null) {
            details.put("reason", a.declineReason());
        }
        return AuditEntry.of(actor, "AUTHORIZATION_DECIDED", "authorization", a.id().toString(), details);
    }
}
