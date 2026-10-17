package dev.cardrail.sw.adapter.messaging;

import dev.cardrail.sw.port.OutboxMessage;
import dev.cardrail.sw.port.OutboxPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One topic per event type, keyed by card token so a card's events stay ordered within a partition. */
public class KafkaOutboxPublisher implements OutboxPublisher {

    public static final String EVENT_ID_HEADER = "cardrail-event-id";
    public static final String EVENT_TYPE_HEADER = "cardrail-event-type";

    private final KafkaTemplate<String, String> kafka;
    private final String topicPrefix;
    private final Duration sendTimeout;

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafka, String topicPrefix, Duration sendTimeout) {
        this.kafka = kafka;
        this.topicPrefix = topicPrefix;
        this.sendTimeout = sendTimeout;
    }

    public static String topic(String prefix, String eventType) {
        return prefix + eventType;
    }

    @Override
    public void publish(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic(topicPrefix, message.eventType()),
                message.messageKey(), message.payloadJson());
        record.headers().add(EVENT_ID_HEADER, Long.toString(message.id()).getBytes(StandardCharsets.UTF_8));
        record.headers().add(EVENT_TYPE_HEADER, message.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing outbox message " + message.id(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("broker did not acknowledge outbox message " + message.id(), e);
        }
    }
}
