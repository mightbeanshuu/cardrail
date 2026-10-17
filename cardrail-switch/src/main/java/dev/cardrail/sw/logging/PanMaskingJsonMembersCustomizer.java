package dev.cardrail.sw.logging;

import dev.cardrail.sw.domain.card.PanMasking;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Structured (JSON) logs bypass the pattern converters, so the same masking is applied here to every
 * string value: message, MDC entries and stack trace alike.
 */
public class PanMaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, PanMasking::maskPans));
    }
}
