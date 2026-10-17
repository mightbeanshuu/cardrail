package dev.cardrail.sw.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@CardrailIntegrationTest
class WebApiIntegrationTest {

    private static final String PAN = "5555555555554444";
    private static final AtomicInteger STAN = new AtomicInteger(500_000);

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        fixtures.reset();
        fixtures.openAccount(PAN, 50_000);
    }

    @Test
    void approvalAndBusinessDeclineAreBothHttp200() throws Exception {
        mvc.perform(post("/v1/authorizations").contentType(MediaType.APPLICATION_JSON).content(body(20_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.maskedPan").value("555555******4444"));

        mvc.perform(post("/v1/authorizations").contentType(MediaType.APPLICATION_JSON).content(body(40_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("51"))
                .andExpect(jsonPath("$.responseCodeName").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.authCode").doesNotExist());
    }

    @Test
    void lookupReversalAndCaptureRoundTrip() throws Exception {
        UUID first = authorize(10_000);
        UUID second = authorize(10_000);

        mvc.perform(get("/v1/authorizations/{id}", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.amountMinor").value(10_000));

        mvc.perform(post("/v1/authorizations/{id}/reversal", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REVERSED"))
                .andExpect(jsonPath("$.releasedMinor").value(10_000));

        mvc.perform(post("/v1/authorizations/{id}/capture", second)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\": 7500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedMinor").value(7_500))
                .andExpect(jsonPath("$.releasedMinor").value(2_500));

        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(42_500);
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        mvc.perform(post("/v1/authorizations").contentType(MediaType.APPLICATION_JSON).content("{\"pan\": \"5555555555554444\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors", hasItem("amountMinor: must not be null")));

        mvc.perform(get("/v1/authorizations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));

        UUID approved = authorize(10_000);
        mvc.perform(post("/v1/authorizations/{id}/capture", approved)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\": 10001}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void auditVerificationMetricsAndOpenApiAreExposed() throws Exception {
        authorize(1_000);

        mvc.perform(get("/v1/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.recordsVerified").value(1));

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cardrail_authorization_latency_seconds_bucket")))
                .andExpect(content().string(containsString("response_code=\"00\"")));

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/authorizations/{id}/reversal']").exists());
    }

    @Test
    void correlationIdIsEchoed() throws Exception {
        mvc.perform(get("/v1/audit/verify").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    private UUID authorize(long amount) throws Exception {
        MvcResult result = mvc.perform(post("/v1/authorizations").contentType(MediaType.APPLICATION_JSON).content(body(amount)))
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andReturn();
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    private static String body(long amount) {
        return """
                {"pan": "%s", "expiry": "3012", "amountMinor": %d, "currency": 356, "mcc": "5411",
                 "merchantId": "MERCHANT0000001", "merchantName": "Fresh Mart Pune", "merchantCountry": "356",
                 "terminalId": "TERM0001", "acquirerId": "40001", "stan": "%06d", "rrn": "R%011d",
                 "transmissionDateTime": "1017103000", "posEntryMode": "051"}
                """.formatted(PAN, amount, STAN.incrementAndGet(), STAN.get());
    }
}
