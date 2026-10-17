package dev.cardrail.sw.application;

import dev.cardrail.sw.port.OutboxMessage;
import dev.cardrail.sw.port.OutboxPublisher;
import dev.cardrail.sw.port.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Publishes committed outbox rows. Delivery is at least once: if the broker acknowledges but marking
 * the row fails, the row is sent again, so consumers deduplicate on the outbox id header.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final OutboxPublisher publisher;
    private final OutboxProperties properties;
    private final TransactionTemplate transactions;
    private final SwitchMetrics metrics;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, OutboxPublisher publisher, OutboxProperties properties,
                       TransactionTemplate transactions, SwitchMetrics metrics, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.transactions = transactions;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cardrail.outbox.poll-interval}")
    public int publishPending() {
        Integer published = transactions.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    private int publishBatch() {
        List<OutboxMessage> batch = outbox.lockUnpublished(properties.batchSize(), properties.maxAttempts());
        // Events for one card share a key; once one fails, later ones for that key wait for the next
        // run so consumers never see them out of order.
        Set<String> blockedKeys = new HashSet<>();
        int published = 0;
        for (OutboxMessage message : batch) {
            if (blockedKeys.contains(message.messageKey())) {
                continue;
            }
            try {
                publisher.publish(message);
                outbox.markPublished(message.id(), clock.instant());
                metrics.outboxPublished(true);
                published++;
            } catch (RuntimeException e) {
                blockedKeys.add(message.messageKey());
                outbox.markFailed(message.id(), e.toString());
                metrics.outboxPublished(false);
                log.warn("outbox message {} ({}) failed on attempt {}: {}", message.id(), message.eventType(),
                        message.attempts() + 1, e.toString());
            }
        }
        return published;
    }
}
