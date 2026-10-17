package dev.cardrail.clearing.adapter.messaging;

import dev.cardrail.clearing.application.ClearingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cardrail.events.transport", havingValue = "kafka", matchIfMissing = true)
class AuthorizationEventListener {

    static final String LISTENER_ID = "clearing-authorization-events";

    private final SwitchEventHandler handler;

    AuthorizationEventListener(SwitchEventHandler handler) {
        this.handler = handler;
    }

    /** The offset is acknowledged only after the clearing transaction has committed. */
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = {
            "${cardrail.events.topic-prefix:cardrail.}" + ClearingService.CAPTURED,
            "${cardrail.events.topic-prefix:cardrail.}" + ClearingService.REVERSED})
    void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String source = record.topic() + "-" + record.partition() + "@" + record.offset();
        handler.handle(record.topic(), record.value(), source);
        ack.acknowledge();
    }
}
