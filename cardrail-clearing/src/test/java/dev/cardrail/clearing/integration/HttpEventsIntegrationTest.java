package dev.cardrail.clearing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.clearing.support.ClearingIntegrationTest;
import dev.cardrail.clearing.support.SignedEvents;
import dev.cardrail.clearing.support.TestEvents;
import dev.cardrail.clearing.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /v1/events}: the switch's HTTP transport, feeding the same idempotent path as Kafka. */
@ClearingIntegrationTest
class HttpEventsIntegrationTest {

    private static final String SECRET = "clearing-test-only-event-signing-secret-0123456789";
    private static final String CAPTURED = "authorization.captured";
    private static final String REVERSED = "authorization.reversed";

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void aSignedCaptureIsRecordedAndTheSameRequestReplayedChangesNothing() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String body = json(TestEvents.captured(id, 20_000, now));

        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 41, body, now))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));
        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 41, body, now))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        assertThat(fixtures.count("SELECT count(*) FROM clearing_record WHERE authorization_id = ?", id.toString()))
                .isOne();
        assertThat(fixtures.jdbc().sql("SELECT source FROM processed_event WHERE authorization_id = ?")
                .param(id.toString()).query(String.class).single()).isEqualTo("http-event-41");
    }

    @Test
    void aCaptureThenItsReversalNetToZero() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 1, json(TestEvents.captured(id, 30_000, now)), now))
                .andExpect(status().isOk());
        mvc.perform(SignedEvents.request(SECRET, REVERSED, 2, json(TestEvents.reversed(id, 30_000, now)), now))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));

        assertThat(fixtures.count("SELECT count(*) FROM clearing_record WHERE authorization_id = ?", id.toString()))
                .isEqualTo(2);
        assertThat(fixtures.count("SELECT sum(amount_minor) FROM clearing_record WHERE authorization_id = ?",
                id.toString())).isZero();
    }

    @Test
    void aWrongSignatureIsRejectedAndNothingIsStored() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String body = json(TestEvents.captured(id, 20_000, now));

        String otherSecret = "not-the-shared-secret-but-just-as-long-0123456789";
        mvc.perform(SignedEvents.request(otherSecret, CAPTURED, 3, body, now))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("signature does not match"));
        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 3, body, now).header("Cardrail-Signature", "ab".repeat(32)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(fixtures.count("SELECT count(*) FROM clearing_record")).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM processed_event")).isZero();
    }

    @Test
    void aStaleRequestIsRejectedEvenWithAValidSignature() throws Exception {
        UUID id = UUID.randomUUID();
        Instant signedAt = Instant.now().minus(Duration.ofMinutes(6));

        String body = json(TestEvents.captured(id, 20_000, signedAt));
        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 4, body, signedAt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("timestamp is more than 5 minutes from this service's clock"));

        assertThat(fixtures.count("SELECT count(*) FROM clearing_record")).isZero();
    }

    @Test
    void aSignedButInvalidEventIs422SoItIsNotMistakenForDelivered() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> noAmount = TestEvents.captured(UUID.randomUUID(), 20_000, now);
        noAmount.remove("capturedAmountMinor");

        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 5, json(noAmount), now))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("capturedAmountMinor")));
        String captureBody = json(TestEvents.captured(UUID.randomUUID(), 1, now));
        mvc.perform(SignedEvents.request(SECRET, REVERSED, 6, captureBody, now))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("does not match topic")));
        String approved = "{\"eventType\":\"authorization.approved\"}";
        mvc.perform(SignedEvents.request(SECRET, "authorization.approved", 7, approved, now))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 8, "not json", now))
                .andExpect(status().isUnprocessableEntity());

        assertThat(fixtures.count("SELECT count(*) FROM processed_event")).isZero();
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItsSignatureIsChecked() throws Exception {
        Instant now = Instant.now();
        String huge = "{\"padding\":\"" + "x".repeat(20_000) + "\"}";

        mvc.perform(SignedEvents.request(SECRET, CAPTURED, 9, huge, now))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private String json(Map<String, Object> event) throws Exception {
        return json.writeValueAsString(event);
    }
}
