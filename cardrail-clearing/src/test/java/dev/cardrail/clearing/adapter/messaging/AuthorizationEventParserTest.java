package dev.cardrail.clearing.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.clearing.support.TestEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationEventParserTest {

    private final ObjectMapper json = new Jackson2ObjectMapperBuilder().build();
    private final AuthorizationEventParser parser = new AuthorizationEventParser(json, SwitchEventContractTest.properties());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{not json", "[1,2]", "null", "{\"authorizationId\":\"not-a-uuid\"}"})
    void unreadableMessagesArePoison(String value) {
        assertThatThrownBy(() -> parser.read(TestEvents.CAPTURED_TOPIC, value)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void eventTypeMustMatchTheTopic() throws Exception {
        String reversed = json.writeValueAsString(TestEvents.reversed(UUID.randomUUID(), 100, Instant.now()));

        assertThatThrownBy(() -> parser.read(TestEvents.CAPTURED_TOPIC, reversed))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("does not match topic");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "authorizationId,     NULL",
            "occurredAt,          NULL"
    })
    void identityAndTimeAreAlwaysRequired(String field, String value) {
        Map<String, Object> event = TestEvents.reversed(UUID.randomUUID(), 100, Instant.now());
        event.put(field, value);

        assertThatThrownBy(() -> parser.read(TestEvents.REVERSED_TOPIC, json.writeValueAsString(event)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(field + " is required");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(nullValues = "NULL", value = {
            "maskedPan,           NULL",
            "maskedPan,           5555554444",
            "maskedPan,           5555551234564444",
            "token,               NULL",
            "token,               ''",
            "acquirerId,          ACQ1",
            "acquirerId,          123456789012",
            "merchantId,          '   '",
            "merchantId,          MERCHANT00000001",
            "mcc,                 541",
            "currency,            0",
            "currency,            1000",
            "capturedAmountMinor, 0",
            "capturedAmountMinor, -5",
            "capturedAmountMinor, NULL"
    })
    void capturesMissingWhatClearingNeedsArePoison(String field, String value) throws Exception {
        Map<String, Object> event = TestEvents.captured(UUID.randomUUID(), 100, Instant.now());
        if (value == null) {
            event.remove(field);
        } else {
            event.put(field, field.equals("currency") || field.equals("capturedAmountMinor") ? Long.parseLong(value) : value);
        }
        AuthorizationEvent parsed = parser.read(TestEvents.CAPTURED_TOPIC, json.writeValueAsString(event));

        assertThatThrownBy(() -> parser.captured(parsed))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(field);
    }
}
