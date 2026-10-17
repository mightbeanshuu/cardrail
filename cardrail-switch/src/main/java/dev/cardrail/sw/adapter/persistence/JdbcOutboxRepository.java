package dev.cardrail.sw.adapter.persistence;

import dev.cardrail.sw.port.OutboxEvent;
import dev.cardrail.sw.port.OutboxMessage;
import dev.cardrail.sw.port.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static dev.cardrail.sw.adapter.persistence.SqlTypes.timestamp;

@Repository
class JdbcOutboxRepository implements OutboxRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;

    JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxEvent event) {
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, event_type, message_key, payload)
                        VALUES (:aggregateType, :aggregateId, :eventType, :messageKey, CAST(:payload AS jsonb))
                        """)
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("messageKey", event.messageKey())
                .param("payload", event.payloadJson())
                .update();
    }

    @Override
    public List<OutboxMessage> lockUnpublished(int limit, int maxAttempts) {
        return jdbc.sql("""
                        SELECT id, event_type, message_key, payload::text AS payload, attempts
                          FROM outbox
                         WHERE published_at IS NULL AND attempts < :maxAttempts
                         ORDER BY id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("maxAttempts", maxAttempts)
                .param("limit", limit)
                .query((rs, n) -> new OutboxMessage(rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("message_key"), rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    @Override
    public void markPublished(long id, Instant publishedAt) {
        jdbc.sql("UPDATE outbox SET published_at = :at, attempts = attempts + 1, last_error = NULL WHERE id = :id")
                .param("at", timestamp(publishedAt))
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(long id, String error) {
        String truncated = error == null ? "unknown" : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", truncated)
                .param("id", id)
                .update();
    }
}
