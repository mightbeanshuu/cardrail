package dev.cardrail.sw.port;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {

    /** Appends in the caller's transaction, so the event exists if and only if the state change commits. */
    void append(OutboxEvent event);

    /** Locks unpublished messages in id order, skipping rows locked by another relay instance. */
    List<OutboxMessage> lockUnpublished(int limit, int maxAttempts);

    void markPublished(long id, Instant publishedAt);

    void markFailed(long id, String error);
}
