package dev.cardrail.clearing.adapter.persistence;

import dev.cardrail.clearing.port.ProcessedEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

import static dev.cardrail.clearing.adapter.persistence.SqlTypes.timestamp;

@Repository
class JdbcProcessedEvents implements ProcessedEvents {

    private final JdbcClient jdbc;

    JdbcProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean contains(UUID authorizationId, String eventType) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE authorization_id = :id AND event_type = :type")
                .param("id", authorizationId.toString())
                .param("type", eventType)
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public void add(UUID authorizationId, String eventType, Outcome outcome, String detail, String source, Instant at) {
        jdbc.sql("""
                        INSERT INTO processed_event (authorization_id, event_type, outcome, detail, source, processed_at)
                        VALUES (:id, :type, :outcome, :detail, :source, :at)
                        """)
                .param("id", authorizationId.toString())
                .param("type", eventType)
                .param("outcome", outcome.name())
                .param("detail", detail)
                .param("source", source)
                .param("at", timestamp(at))
                .update();
    }
}
