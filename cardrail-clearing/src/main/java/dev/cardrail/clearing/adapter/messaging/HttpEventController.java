package dev.cardrail.clearing.adapter.messaging;

import dev.cardrail.clearing.application.EventOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.regex.Pattern;

/**
 * Receives switch events over HTTP ({@code cardrail.events.http.enabled=true}) and feeds them to the
 * same handler as the Kafka listener, so they are idempotent in the same way. 200 means the event is
 * durably processed, or was already; anything else makes the switch keep it and send it again.
 */
@RestController
@ConditionalOnProperty(name = "cardrail.events.http.enabled", havingValue = "true")
class HttpEventController {

    static final String EVENT_ID_HEADER = "Cardrail-Event-Id";
    static final String EVENT_TYPE_HEADER = "Cardrail-Event-Type";
    static final String TIMESTAMP_HEADER = "Cardrail-Timestamp";
    static final String SIGNATURE_HEADER = "Cardrail-Signature";

    static final int MAX_BODY_BYTES = 16 * 1024;

    private static final Pattern OUTBOX_ID = Pattern.compile("\\d{1,19}");
    private static final Logger log = LoggerFactory.getLogger(HttpEventController.class);

    private final EventSignatureVerifier verifier;
    private final SwitchEventHandler handler;
    private final String topicPrefix;

    HttpEventController(HttpEventsProperties http, EventsProperties events, SwitchEventHandler handler, Clock clock) {
        this.verifier = new EventSignatureVerifier(http.secret(), http.tolerance(), clock);
        this.handler = handler;
        this.topicPrefix = events.topicPrefix();
    }

    record Accepted(EventOutcome outcome) {
    }

    private static final class EventTooLargeException extends RuntimeException {
    }

    @PostMapping(path = "/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    Accepted receive(@RequestHeader(name = TIMESTAMP_HEADER, required = false) String timestamp,
                     @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
                     @RequestHeader(name = EVENT_TYPE_HEADER, required = false) String eventType,
                     @RequestHeader(name = EVENT_ID_HEADER, required = false) String eventId,
                     InputStream request) throws IOException {
        // The endpoint is public; read no more than an event can be before the signature is checked.
        byte[] body = request.readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new EventTooLargeException();
        }
        verifier.verify(timestamp, signature, body);
        if (eventType == null) {
            throw new InvalidEventException("the " + EVENT_TYPE_HEADER + " header is required");
        }
        // The id header is not signed, so it is recorded only if it looks like an outbox id.
        boolean numbered = eventId != null && OUTBOX_ID.matcher(eventId).matches();
        String source = "http-event-" + (numbered ? eventId : "unnumbered");
        return new Accepted(handler.handle(topicPrefix + eventType, new String(body, StandardCharsets.UTF_8), source));
    }

    @ExceptionHandler(EventTooLargeException.class)
    ProblemDetail tooLarge() {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Event too large",
                "An event is at most " + MAX_BODY_BYTES + " bytes.");
    }

    @ExceptionHandler(InvalidSignatureException.class)
    ProblemDetail invalidSignature(InvalidSignatureException e) {
        log.warn("rejected an event: {}", e.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Invalid event signature", e.getMessage());
    }

    /** Sending it again cannot help; the switch keeps it in its outbox for an operator after its last attempt. */
    @ExceptionHandler(InvalidEventException.class)
    ProblemDetail invalidEvent(InvalidEventException e) {
        log.warn("rejected an event: {}", e.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid event", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
