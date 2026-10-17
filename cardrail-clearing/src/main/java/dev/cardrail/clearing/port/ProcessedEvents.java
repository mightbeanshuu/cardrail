package dev.cardrail.clearing.port;

import java.time.Instant;
import java.util.UUID;

/** The consumer's idempotency ledger, keyed by (authorizationId, eventType). */
public interface ProcessedEvents {

    enum Outcome { RECORDED, IGNORED }

    boolean contains(UUID authorizationId, String eventType);

    /** @throws org.springframework.dao.DuplicateKeyException if the event was already processed */
    void add(UUID authorizationId, String eventType, Outcome outcome, String detail, String source, Instant at);
}
