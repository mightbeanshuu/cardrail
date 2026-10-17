package dev.cardrail.sw.domain.audit;

import java.time.Instant;
import java.util.SortedMap;
import java.util.TreeMap;

/** One row of the audit chain. {@code occurredAt} must already be truncated to microseconds. */
public record AuditRecord(
        long seq,
        Instant occurredAt,
        String actor,
        String action,
        String entity,
        String entityId,
        SortedMap<String, String> details,
        String prevHash,
        String hash) {

    public AuditRecord {
        details = new TreeMap<>(details);
    }

    public static AuditRecord chain(long seq, Instant occurredAt, AuditEntry entry, String prevHash) {
        String hash = AuditHasher.hash(seq, occurredAt, entry.actor(), entry.action(), entry.entity(),
                entry.entityId(), entry.details(), prevHash);
        return new AuditRecord(seq, occurredAt, entry.actor(), entry.action(), entry.entity(), entry.entityId(),
                entry.details(), prevHash, hash);
    }

    public String recomputeHash() {
        return AuditHasher.hash(seq, occurredAt, actor, action, entity, entityId, details, prevHash);
    }
}
