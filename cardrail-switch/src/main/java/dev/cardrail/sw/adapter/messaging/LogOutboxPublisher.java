package dev.cardrail.sw.adapter.messaging;

import dev.cardrail.sw.port.OutboxMessage;
import dev.cardrail.sw.port.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** For running locally without a broker: "publishes" by logging, so the relay and outbox still run. */
public class LogOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogOutboxPublisher.class);

    @Override
    public void publish(OutboxMessage message) {
        log.info("outbox {} {} key={} {}", message.id(), message.eventType(), message.messageKey(), message.payloadJson());
    }
}
