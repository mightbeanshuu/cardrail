package dev.cardrail.clearing.adapter.messaging;

import dev.cardrail.clearing.application.ClearingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
class AuthorizationEventListener {

    static final String LISTENER_ID = "clearing-authorization-events";

    private final AuthorizationEventParser parser;
    private final ClearingService clearing;

    AuthorizationEventListener(AuthorizationEventParser parser, ClearingService clearing) {
        this.parser = parser;
        this.clearing = clearing;
    }

    /** The offset is acknowledged only after the clearing transaction has committed. */
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = {
            "${cardrail.events.topic-prefix:cardrail.}" + ClearingService.CAPTURED,
            "${cardrail.events.topic-prefix:cardrail.}" + ClearingService.REVERSED})
    void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        AuthorizationEvent event = parser.read(record.topic(), record.value());
        String source = record.topic() + "-" + record.partition() + "@" + record.offset();
        switch (event.eventType()) {
            case ClearingService.CAPTURED -> clearing.onCaptured(parser.captured(event), source);
            case ClearingService.REVERSED -> clearing.onReversed(parser.reversed(event), source);
            default -> throw new IllegalStateException("parser admitted " + event.eventType());
        }
        ack.acknowledge();
    }
}
