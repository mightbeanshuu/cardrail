package dev.cardrail.clearing.adapter.messaging;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.clearing.application.CapturedAuthorization;
import dev.cardrail.clearing.application.ClearingService;
import dev.cardrail.clearing.application.ReversedAuthorization;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads and validates switch events. Anything that fails here is a poison message. */
@Component
public class AuthorizationEventParser {

    static final Set<String> HANDLED = Set.of(ClearingService.CAPTURED, ClearingService.REVERSED);

    private static final Pattern MASKED_PAN = Pattern.compile("\\d{6}\\*{2,9}\\d{4}");
    private static final Pattern ACQUIRER_ID = Pattern.compile("\\d{1,11}");
    private static final Pattern MCC = Pattern.compile("\\d{4}");
    private static final int MAX_TOKEN = 64;
    private static final int MAX_MERCHANT_ID = 15;

    private final ObjectMapper json;
    private final String topicPrefix;

    public AuthorizationEventParser(ObjectMapper json, EventsProperties properties) {
        this.json = json;
        this.topicPrefix = properties.topicPrefix();
    }

    public AuthorizationEvent read(String topic, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("empty message on " + topic);
        }
        AuthorizationEvent event;
        try {
            event = json.readValue(value, AuthorizationEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("unreadable JSON on " + topic + ": " + e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new InvalidEventException("null event on " + topic);
        }
        String expectedType = topic.startsWith(topicPrefix) ? topic.substring(topicPrefix.length()) : topic;
        List<String> problems = new ArrayList<>();
        if (!expectedType.equals(event.eventType())) {
            problems.add("eventType '" + event.eventType() + "' does not match topic " + topic);
        } else if (!HANDLED.contains(event.eventType())) {
            problems.add("eventType '" + event.eventType() + "' is not consumed by clearing");
        }
        require(problems, event.authorizationId() != null, "authorizationId is required");
        require(problems, event.occurredAt() != null, "occurredAt is required");
        failIfAny(problems, event);
        return event;
    }

    public CapturedAuthorization captured(AuthorizationEvent e) {
        List<String> problems = new ArrayList<>();
        require(problems, e.token() != null && !e.token().isBlank() && e.token().length() <= MAX_TOKEN,
                "token is required, at most " + MAX_TOKEN + " characters");
        require(problems, e.maskedPan() != null && MASKED_PAN.matcher(e.maskedPan()).matches(),
                "maskedPan must be first 6 + asterisks + last 4");
        require(problems, e.acquirerId() != null && ACQUIRER_ID.matcher(e.acquirerId()).matches(),
                "acquirerId must be 1 to 11 digits");
        require(problems, e.merchantId() != null && !e.merchantId().isBlank()
                && e.merchantId().length() <= MAX_MERCHANT_ID, "merchantId is required, at most 15 characters");
        require(problems, e.mcc() != null && MCC.matcher(e.mcc()).matches(), "mcc must be 4 digits");
        require(problems, e.currency() != null && e.currency() > 0 && e.currency() < 1000,
                "currency must be an ISO 4217 numeric code");
        require(problems, e.capturedAmountMinor() != null && e.capturedAmountMinor() > 0,
                "capturedAmountMinor must be positive");
        failIfAny(problems, e);
        return new CapturedAuthorization(e.authorizationId(), e.token(), e.maskedPan(), e.acquirerId(),
                e.merchantId().strip(), e.mcc(), e.currency(), e.capturedAmountMinor(), e.occurredAt());
    }

    public ReversedAuthorization reversed(AuthorizationEvent e) {
        return new ReversedAuthorization(e.authorizationId(), e.occurredAt());
    }

    private static void require(List<String> problems, boolean condition, String problem) {
        if (!condition) {
            problems.add(problem);
        }
    }

    private static void failIfAny(List<String> problems, AuthorizationEvent event) {
        if (!problems.isEmpty()) {
            throw new InvalidEventException("invalid " + event.eventType() + " " + event.authorizationId() + ": "
                    + String.join("; ", problems));
        }
    }
}
