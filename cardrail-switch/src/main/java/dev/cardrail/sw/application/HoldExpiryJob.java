package dev.cardrail.sw.application;

import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.domain.ledger.JournalKind;
import dev.cardrail.sw.port.AuditTrail;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.port.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Releases holds that were never captured or reversed. Batches are claimed with SKIP LOCKED, so any
 * number of switch instances can run this concurrently without processing a row twice.
 */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final String ACTOR = "scheduler:hold-expiry";

    private final AuthorizationRepository authorizations;
    private final IssuerLedger ledger;
    private final OutboxRepository outbox;
    private final AuditTrail audit;
    private final AuthorizationEvents events;
    private final HoldProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public HoldExpiryJob(AuthorizationRepository authorizations, IssuerLedger ledger, OutboxRepository outbox,
                         AuditTrail audit, AuthorizationEvents events, HoldProperties properties,
                         TransactionTemplate transactions, Clock clock) {
        this.authorizations = authorizations;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cardrail.holds.sweep-interval}")
    public int expireHolds() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.expiryDays()));
        int total = 0;
        int expired;
        do {
            expired = transactions.execute(status -> expireBatch(cutoff));
            total += expired;
        } while (expired == properties.batchSize());
        if (total > 0) {
            log.info("expired {} holds older than {}", total, cutoff);
        }
        return total;
    }

    private int expireBatch(Instant cutoff) {
        List<StoredAuthorization> batch = new ArrayList<>(authorizations.lockExpirable(cutoff, properties.batchSize()));
        // A batch locks several accounts. Taking them in token order means two sweepers whose batches
        // share accounts cannot wait on each other in a cycle.
        batch.sort(Comparator.comparing(StoredAuthorization::token));
        Instant now = clock.instant();
        List<AuditEntry> entries = new ArrayList<>(batch.size());
        for (StoredAuthorization a : batch) {
            long released = ledger.releaseHold(a.id(), JournalKind.HOLD_EXPIRY);
            StoredAuthorization expired = authorizations.markExpired(a.id(), now);
            outbox.append(events.stateChanged(AuthorizationEvents.EXPIRED, expired, now));
            entries.add(AuditEntry.of(ACTOR, "HOLD_EXPIRED", "authorization", a.id().toString(),
                    Map.of("releasedMinor", Long.toString(released), "currency", Integer.toString(a.currency()))));
        }
        // Audit appends take the chain lock until commit; doing them after every account lock keeps
        // the lock order the same as in every other transaction (audit last).
        entries.forEach(audit::append);
        return batch.size();
    }
}
