package dev.cardrail.sw.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.sw.adapter.issuer.SimulatedIssuer;
import dev.cardrail.sw.adapter.issuer.SimulatedIssuerFaults;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Test profile: issuer timeout 1500 ms; circuit opens at 50% failures over at least 5 calls. */
@CardrailIntegrationTest
class StandInIntegrationTest {

    /** BIN 555555 has a stand-in limit of INR 5,000.00 in the seed data. */
    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;
    @Autowired SimulatedIssuer issuer;
    @Autowired MeterRegistry meters;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        fixtures.reset();
        fixtures.openAccount(PAN, 1_000_000);
    }

    @Test
    void slowIssuerUnderTheStandInLimitIsApprovedByTheSwitchWithoutAHold() throws Exception {
        fixtures.faults().set(new SimulatedIssuerFaults.Settings(Duration.ofMillis(2_200), 0));

        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 200_000).build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.APPROVED);
        assertThat(result.standIn()).isTrue();
        assertThat(result.authCode()).hasSize(6);
        assertThat(fixtures.outboxEventTypes(result.id())).containsExactly("authorization.approved", "stip.advice");
        assertThat(advice(result.id()).get("standInTrigger").asText()).isEqualTo("TIMEOUT");

        Thread.sleep(1_000);
        assertThat(fixtures.balance(PAN).heldMinor())
                .as("the timed-out issuer call was cancelled before it touched the account")
                .isZero();
    }

    @Test
    void slowIssuerOverTheStandInLimitIsDeclined91() throws Exception {
        fixtures.faults().set(new SimulatedIssuerFaults.Settings(Duration.ofMillis(2_200), 0));

        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 500_001).build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.ISSUER_UNAVAILABLE);
        assertThat(result.standIn()).isTrue();
        assertThat(fixtures.outboxEventTypes(result.id())).containsExactly("authorization.declined", "stip.advice");
    }

    @Test
    void circuitOpensAfterTheConfiguredFailuresAndThenTheIssuerIsNotCalled() throws Exception {
        fixtures.faults().set(new SimulatedIssuerFaults.Settings(Duration.ZERO, 1.0));
        double openBefore = stipCount("circuit_open");

        for (int i = 0; i < 5; i++) {
            AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 1_000).build());
            assertThat(result.standIn()).isTrue();
            assertThat(result.responseCode()).isEqualTo(ResponseCode.APPROVED);
        }
        assertThat(fixtures.issuerCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long callsWhenOpened = issuer.calls();

        AuthorizationResult shortCircuited = authorize.authorize(RequestBuilder.purchase(PAN, 1_000).build());

        assertThat(shortCircuited.standIn()).isTrue();
        assertThat(issuer.calls()).isEqualTo(callsWhenOpened);
        assertThat(advice(shortCircuited.id()).get("standInTrigger").asText()).isEqualTo("CIRCUIT_OPEN");
        assertThat(stipCount("circuit_open") - openBefore).isEqualTo(1);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
    }

    private double stipCount(String trigger) {
        var counter = meters.find("cardrail.stip.decisions").tag("trigger", trigger).tag("outcome", "approved").counter();
        return counter == null ? 0 : counter.count();
    }

    private JsonNode advice(UUID id) throws Exception {
        String payload = fixtures.jdbc().sql("""
                        SELECT payload::text FROM outbox WHERE aggregate_id = :id AND event_type = 'stip.advice'
                        """)
                .param("id", id.toString())
                .query(String.class)
                .single();
        return json.readTree(payload);
    }
}
