package dev.cardrail.clearing.adapter.messaging;

import dev.cardrail.clearing.application.ClearingService;
import dev.cardrail.clearing.application.EventOutcome;
import org.springframework.stereotype.Component;

/** What both transports do with a switch event once it has arrived: parse it and hand it to clearing. */
@Component
class SwitchEventHandler {

    private final AuthorizationEventParser parser;
    private final ClearingService clearing;

    SwitchEventHandler(AuthorizationEventParser parser, ClearingService clearing) {
        this.parser = parser;
        this.clearing = clearing;
    }

    /**
     * @param topic  the Kafka topic, or for HTTP the topic the event type would have
     * @param source where the event came from, recorded with it
     * @throws InvalidEventException for an event that can never be processed as sent
     */
    EventOutcome handle(String topic, String value, String source) {
        AuthorizationEvent event = parser.read(topic, value);
        return switch (event.eventType()) {
            case ClearingService.CAPTURED -> clearing.onCaptured(parser.captured(event), source);
            case ClearingService.REVERSED -> clearing.onReversed(parser.reversed(event), source);
            default -> throw new IllegalStateException("parser admitted " + event.eventType());
        };
    }
}
