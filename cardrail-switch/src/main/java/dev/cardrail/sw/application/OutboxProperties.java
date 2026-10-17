package dev.cardrail.sw.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param publisher   {@code log} (no broker needed), {@code kafka}, or {@code http} (posts to clearing)
 * @param topicPrefix topic name is prefix + event type, e.g. {@code cardrail.authorization.approved}
 * @param maxAttempts after this many failed sends a message is left for an operator to inspect
 */
@ConfigurationProperties("cardrail.outbox")
public record OutboxProperties(
        String publisher,
        int batchSize,
        int maxAttempts,
        String topicPrefix,
        Duration sendTimeout,
        int topicPartitions,
        short topicReplicas) {
}
