package dev.cardrail.clearing.integration;

import dev.cardrail.clearing.application.SettlementProperties;
import dev.cardrail.clearing.support.ClearingIntegrationTest;
import dev.cardrail.clearing.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ClearingIntegrationTest
class WebApiIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2025, 3, 14);

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired SettlementProperties properties;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void listsClearingRecordsOfABusinessDateInPages() throws Exception {
        SettlementDataset.load(fixtures, DAY);

        mvc.perform(get("/v1/clearing-records").param("date", "2025-03-14").param("page", "1").param("size", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(4))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].recordType").value("CAPTURE"))
                .andExpect(jsonPath("$.items[0].interchangeCategory").value("DEFAULT"))
                .andExpect(jsonPath("$.items[1].recordType").value("REVERSAL"))
                .andExpect(jsonPath("$.items[1].amountMinor").value(-10_000))
                .andExpect(jsonPath("$.items[1].interchangeFeeMinor").value(-175))
                .andExpect(jsonPath("$.items[1].maskedPan").value("555555******4444"))
                .andExpect(jsonPath("$.items[1].businessDate").value("2025-03-14"))
                .andExpect(jsonPath("$.items[1].status").value("PENDING_SETTLEMENT"))
                .andExpect(jsonPath("$.items[1].settledAt").doesNotExist());

        mvc.perform(get("/v1/clearing-records").param("date", "2025-03-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void badQueriesAreProblemDetails() throws Exception {
        mvc.perform(get("/v1/clearing-records"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/v1/clearing-records").param("date", "14-03-2025"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/v1/clearing-records").param("date", "2025-03-14").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/v1/settlement-runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("businessDate")));
        mvc.perform(post("/v1/settlement-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + LocalDate.now().plusDays(3) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Business date not started"));
    }

    @Test
    void settlementRunLifecycle() throws Exception {
        SettlementDataset.load(fixtures, DAY);

        mvc.perform(get("/v1/settlement-runs/2025-03-14"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/v1/settlement-runs/2025-03-14/report.csv"))
                .andExpect(status().isNotFound());

        String body = "{\"businessDate\":\"2025-03-14\"}";
        mvc.perform(post("/v1/settlement-runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/v1/settlement-runs/2025-03-14"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recordCount").value(6))
                .andExpect(jsonPath("$.positions.length()").value(8))
                .andExpect(jsonPath("$.positions[0].partyType").value("ACQUIRER"))
                .andExpect(jsonPath("$.positions[0].partyId").value("111111"))
                .andExpect(jsonPath("$.positions[0].netMinor").value(317_780))
                .andExpect(jsonPath("$.reportUrl").value("/v1/settlement-runs/2025-03-14/report.csv"));

        mvc.perform(post("/v1/settlement-runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recordCount").value(6));

        mvc.perform(get("/v1/settlement-runs/2025-03-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2025-03-14"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.finishedAt").exists())
                .andExpect(jsonPath("$.failure").doesNotExist())
                .andExpect(jsonPath("$.positions[7].partyType").value("NETWORK"))
                .andExpect(jsonPath("$.positions[7].netMinor").value(0));

        String csv = mvc.perform(get("/v1/settlement-runs/2025-03-14/report.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("settlement-2025-03-14.csv")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).isEqualTo(SettlementDataset.expectedCsv(DAY))
                .isEqualTo(Files.readString(properties.reportDirectory().resolve("settlement-2025-03-14.csv")));

        mvc.perform(get("/v1/clearing-records").param("date", "2025-03-14"))
                .andExpect(jsonPath("$.items[0].status").value("SETTLED"))
                .andExpect(jsonPath("$.items[0].settlementDate").value("2025-03-14"));
    }

    @Test
    void theConsoleOriginIsAllowedAndOthersAreNot() throws Exception {
        mvc.perform(options("/v1/settlement-runs")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"));
        mvc.perform(get("/v1/clearing-records").param("date", "2025-03-14")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"));
        mvc.perform(options("/v1/settlement-runs")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
