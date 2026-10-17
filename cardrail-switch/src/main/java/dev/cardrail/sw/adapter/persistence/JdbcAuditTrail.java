package dev.cardrail.sw.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.sw.domain.audit.AuditChainVerifier;
import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.audit.AuditHasher;
import dev.cardrail.sw.domain.audit.AuditRecord;
import dev.cardrail.sw.domain.audit.ChainVerification;
import dev.cardrail.sw.port.AuditTrail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static dev.cardrail.sw.adapter.persistence.SqlTypes.instant;
import static dev.cardrail.sw.adapter.persistence.SqlTypes.timestamp;

@Repository
class JdbcAuditTrail implements AuditTrail {

    /** Advisory lock key reserved for the audit chain head. Any constant works; it only has to be unique. */
    private static final long CHAIN_LOCK_KEY = 0x6175646974L;
    private static final int VERIFY_PAGE_SIZE = 1000;
    private static final TypeReference<TreeMap<String, String>> DETAILS_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcAuditTrail(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditEntry entry) {
        // Held until commit. Without it, two transactions could read the same head and both link to it.
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:key)").param("key", CHAIN_LOCK_KEY).query(Integer.class).single();
        String prevHash = jdbc.sql("SELECT hash FROM audit_log ORDER BY seq DESC LIMIT 1")
                .query(String.class)
                .optional()
                .orElse(AuditHasher.GENESIS_HASH);
        long seq = jdbc.sql("SELECT nextval(pg_get_serial_sequence('audit_log', 'seq'))").query(Long.class).single();
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuditRecord record = AuditRecord.chain(seq, occurredAt, entry, prevHash);
        jdbc.sql("""
                        INSERT INTO audit_log (seq, occurred_at, actor, action, entity, entity_id, details, prev_hash, hash)
                        VALUES (:seq, :occurredAt, :actor, :action, :entity, :entityId, CAST(:details AS jsonb),
                                :prevHash, :hash)
                        """)
                .param("seq", record.seq())
                .param("occurredAt", timestamp(record.occurredAt()))
                .param("actor", record.actor())
                .param("action", record.action())
                .param("entity", record.entity())
                .param("entityId", record.entityId())
                .param("details", toJson(record))
                .param("prevHash", record.prevHash())
                .param("hash", record.hash())
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public ChainVerification verify() {
        AuditChainVerifier verifier = new AuditChainVerifier();
        long after = 0;
        while (true) {
            List<AuditRecord> page = jdbc.sql("""
                            SELECT seq, occurred_at, actor, action, entity, entity_id, details::text AS details,
                                   prev_hash, hash
                              FROM audit_log WHERE seq > :after ORDER BY seq LIMIT :limit
                            """)
                    .param("after", after)
                    .param("limit", VERIFY_PAGE_SIZE)
                    .query((rs, n) -> new AuditRecord(rs.getLong("seq"), instant(rs, "occurred_at"),
                            rs.getString("actor"), rs.getString("action"), rs.getString("entity"),
                            rs.getString("entity_id"), parseDetails(rs.getString("details")),
                            rs.getString("prev_hash"), rs.getString("hash")))
                    .list();
            for (AuditRecord record : page) {
                Optional<ChainVerification.Broken> broken = verifier.accept(record);
                if (broken.isPresent()) {
                    return broken.get();
                }
            }
            if (page.size() < VERIFY_PAGE_SIZE) {
                return verifier.intact();
            }
            after = page.getLast().seq();
        }
    }

    private String toJson(AuditRecord record) {
        try {
            return json.writeValueAsString(record.details());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit details are a flat string map and always serialize", e);
        }
    }

    private TreeMap<String, String> parseDetails(String details) {
        try {
            return json.readValue(details, DETAILS_TYPE);
        } catch (JsonProcessingException e) {
            // Unparseable details cannot reproduce the hash, so the verifier reports this row as broken.
            TreeMap<String, String> unreadable = new TreeMap<>();
            unreadable.put("unparseable", details);
            return unreadable;
        }
    }
}
