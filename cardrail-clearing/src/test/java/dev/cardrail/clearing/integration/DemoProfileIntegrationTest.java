package dev.cardrail.clearing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.clearing.support.SignedEvents;
import dev.cardrail.clearing.support.TestEvents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo profile as it runs on Render: its own schema in a database it shares with the switch, no
 * Kafka, events over signed HTTP, and settlement of the current business date. Its own application
 * context, started on an empty schema; PostgreSQL only, like the demo.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=" + DemoProfileIntegrationTest.URL + "?currentSchema=clearing",
        "CARDRAIL_EVENTS_HMAC_SECRET=" + DemoProfileIntegrationTest.SECRET,
        "cardrail.settlement.report-directory=target/demo-settlement-reports"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@DisabledIfEnvironmentVariable(named = "CLEARING_DB", matches = "(?i)oracle")
class DemoProfileIntegrationTest {

    static final String URL = "jdbc:postgresql://localhost:5432/cardrail_clearing_test";
    static final String SECRET = "demo-profile-integration-test-secret-0123456789";
    private static final String CONSOLE = "https://cardrail-demo.vercel.app";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext context;

    /** Runs before the context starts, so Flyway and Spring Batch build the schema from nothing. */
    @BeforeAll
    static void dropTheDemoSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "mac", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS clearing CASCADE");
        }
    }

    @Test
    void aSignedCaptureIsSettledOnTheCurrentBusinessDateWithPositionsThatNetToZero() throws Exception {
        String today = read(mvc.perform(get("/v1/business-date")).andExpect(status().isOk())
                .andExpect(jsonPath("$.zone").value("Asia/Kolkata"))).path("businessDate").asText();
        Instant now = Instant.now();
        String capture = json.writeValueAsString(TestEvents.captured(UUID.randomUUID(), 25_000, now));

        mvc.perform(SignedEvents.request(SECRET, "authorization.captured", 1, capture, now))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));
        JsonNode run = read(mvc.perform(post("/v1/settlement-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + today + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED")));

        assertThat(run.path("positions")).hasSize(3);
        long net = 0;
        for (JsonNode position : run.path("positions")) {
            net += position.path("netMinor").asLong();
        }
        assertThat(net).isZero();
        mvc.perform(get("/v1/settlement-runs/{date}/report.csv", today)).andExpect(status().isOk());
    }

    @Test
    void everythingLivesInTheClearingSchemaIncludingSpringBatchsTables() {
        assertThat(jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                         WHERE table_schema = 'clearing' AND table_name IN
                               ('flyway_schema_history', 'clearing_record', 'batch_job_instance')
                        """)
                .query(String.class).list())
                .containsExactlyInAnyOrder("flyway_schema_history", "clearing_record", "batch_job_instance");
    }

    @Test
    void theKafkaListenerIsNotThere() {
        assertThat(context.containsBean("authorizationEventListener")).isFalse();
        assertThat(context.containsBean("httpEventController")).isTrue();
    }

    @Test
    void theConsoleCanPingHealthCrossOriginToWakeTheService() throws Exception {
        mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, CONSOLE))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE));
    }

    @Test
    void theConsoleHealthPreflightWithCorrelationHeaderIsAllowed() throws Exception {
        // The console adds X-Correlation-Id to every request, so the browser preflights even the health GET.
        mvc.perform(options("/actuator/health")
                        .header(HttpHeaders.ORIGIN, CONSOLE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-correlation-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE));
    }

    @Test
    void theConsoleOriginIsAllowed() throws Exception {
        mvc.perform(options("/v1/settlement-runs")
                        .header(HttpHeaders.ORIGIN, CONSOLE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
