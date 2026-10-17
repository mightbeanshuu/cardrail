package dev.cardrail.clearing.adapter.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.clearing.application.CapturedAuthorization;
import dev.cardrail.clearing.application.ReversedAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses the sample events from docs/CONTRACTS.md section 1 (copied into
 * {@code contract/switch-events.json}) through the same parser the listener uses. If the switch
 * changes its payload, this test and the switch's own contract test must change together.
 */
class SwitchEventContractTest {

    private static final UUID AUTHORIZATION_ID = UUID.fromString("7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b");

    private final ObjectMapper json = new Jackson2ObjectMapperBuilder().build();
    private final AuthorizationEventParser parser = new AuthorizationEventParser(json, properties());

    @Test
    void capturedSampleMapsToACapture() throws IOException {
        AuthorizationEvent event = parser.read("cardrail.authorization.captured", sample("authorization.captured"));

        assertThat(event.eventType()).isEqualTo("authorization.captured");
        assertThat(event.status()).isEqualTo("CAPTURED");
        assertThat(event.amountMinor()).isEqualTo(25_000);
        assertThat(event.standIn()).isFalse();
        assertThat(event.terminalId()).isEqualTo("TERM0001");
        assertThat(event.stan()).isEqualTo("000123");
        assertThat(event.rrn()).isEqualTo("629112000123");

        CapturedAuthorization capture = parser.captured(event);
        assertThat(capture).isEqualTo(new CapturedAuthorization(AUTHORIZATION_ID, "9999991234567890",
                "555555******4444", "123456", "MERCHANT000001", "5411", 356, 20_000,
                Instant.parse("2026-10-18T09:15:30Z")));
    }

    @Test
    void reversedSampleMapsToAReversal() throws IOException {
        AuthorizationEvent event = parser.read("cardrail.authorization.reversed", sample("authorization.reversed"));

        assertThat(event.capturedAmountMinor()).as("omitted on reversals").isNull();
        assertThat(parser.reversed(event))
                .isEqualTo(new ReversedAuthorization(AUTHORIZATION_ID, Instant.parse("2026-10-18T09:20:00Z")));
    }

    @Test
    void fieldsAddedByTheSwitchLaterAreIgnored() throws IOException {
        String withExtra = sample("authorization.captured").replaceFirst("\\{", "{\"schemaVersion\":2,\"newThing\":{\"a\":1},");

        assertThat(parser.captured(parser.read("cardrail.authorization.captured", withExtra)).capturedAmountMinor())
                .isEqualTo(20_000);
    }

    private String sample(String eventType) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contract/switch-events.json")) {
            JsonNode samples = json.readTree(in);
            return json.writeValueAsString(samples.get(eventType));
        }
    }

    static EventsProperties properties() {
        return new EventsProperties("cardrail.", "cardrail.clearing.dlt", 1, (short) 1,
                new EventsProperties.Retry(0, Duration.ZERO, 1.0, Duration.ZERO));
    }
}
