package dev.cardrail.sw.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.cardrail.sw.application.OutboxRelay;
import dev.cardrail.sw.port.AccountStatus;
import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.support.TestFixtures;
import dev.cardrail.sw.support.TestSupportConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo profile as it runs on Render: its own schema, keys from the environment, no Kafka, no ISO
 * port, outbox events posted to clearing over HTTP (here a local stand-in that records what it gets).
 * Its own application context, since the configuration differs from the shared one.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/cardrail_test?currentSchema=switch",
        "spring.flyway.clean-disabled=false",
        "spring.data.redis.database=15",
        "cardrail.scheduling.enabled=false",
        "cardrail.web.rate-limit.burst=" + DemoProfileIntegrationTest.BURST,
        "cardrail.web.rate-limit.requests-per-minute=1"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(TestSupportConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class DemoProfileIntegrationTest {

    static final int BURST = 8;
    private static final String SECRET = "demo-profile-integration-test-secret-0123456789";
    private static final String CONSOLE = "https://cardrail-demo.vercel.app";
    private static final String FUNDED = "5555555555554444";
    private static final String LOW_BALANCE = "5105105105105100";
    private static final String TWO_SERIES = "2223003122003222";
    private static final String BLOCKED = "5425233430109903";
    /** A listed test card with no account, so the issuer itself answers 14. */
    private static final String NO_ACCOUNT = "2223000048410010";
    private static final List<String> TEST_CARDS = List.of(FUNDED, "5200828282828210", TWO_SERIES, LOW_BALANCE,
            BLOCKED);
    /** Luhn-valid, in a Mastercard range, and not a published test number. */
    private static final String UNLISTED = "5412751234123452";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger CLIENT = new AtomicInteger();
    private static final ClearingStub CLEARING = new ClearingStub();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestFixtures fixtures;
    @Autowired CardTokenizer tokenizer;
    @Autowired IssuerLedger ledger;
    @Autowired OutboxRelay relay;
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void demoEnvironment(DynamicPropertyRegistry registry) {
        registry.add("CARDRAIL_EVENTS_HMAC_SECRET", () -> SECRET);
        registry.add("cardrail.outbox.http.base-url", CLEARING::baseUrl);
        registry.add("cardrail.vault.keks[0].id", () -> "demo-it-kek");
        registry.add("cardrail.vault.keks[0].key", DemoProfileIntegrationTest::randomKey);
        registry.add("cardrail.vault.keks[0].status", () -> "ACTIVE");
        registry.add("cardrail.vault.blind-index-key", DemoProfileIntegrationTest::randomKey);
    }

    @AfterAll
    static void stopClearing() {
        CLEARING.stop();
    }

    @Test
    void theTestCardsAreSeededOnceAndASecondRunChangesNothing() throws Exception {
        for (String pan : TEST_CARDS) {
            assertThat(ledger.balance(tokenizer.tokenize(pan).token())).as(pan).isPresent();
        }
        assertThat(ledger.balance(tokenizer.tokenize(BLOCKED).token()).orElseThrow().status())
                .isEqualTo(AccountStatus.BLOCKED);
        assertThat(ledger.balance(tokenizer.tokenize(TWO_SERIES).token()).orElseThrow().status())
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(ledger.balance(tokenizer.tokenize(NO_ACCOUNT).token())).isEmpty();
        long accounts = count("SELECT count(*) FROM issuer_account");
        long available = ledger.balance(tokenizer.tokenize(FUNDED).token()).orElseThrow().availableMinor();

        context.getBean("demoAccountsLoader", ApplicationRunner.class).run(new DefaultApplicationArguments());

        assertThat(count("SELECT count(*) FROM issuer_account")).isEqualTo(accounts).isEqualTo(TEST_CARDS.size());
        assertThat(ledger.balance(tokenizer.tokenize(FUNDED).token()).orElseThrow().availableMinor())
                .isEqualTo(available);
    }

    @Test
    void theTestCardsAnswerAsTheConsoleExpects() throws Exception {
        String client = newClient();
        assertThat(authorize(client, FUNDED, 20_000).path("responseCode").asText()).isEqualTo("00");
        assertThat(authorize(client, TWO_SERIES, 20_000).path("responseCode").asText()).isEqualTo("00");
        assertThat(authorize(client, LOW_BALANCE, 60_000).path("responseCode").asText()).isEqualTo("51");
        assertThat(authorize(client, BLOCKED, 1_000).path("responseCode").asText()).isEqualTo("05");
        assertThat(authorize(client, FUNDED, 1_000, "7995").path("responseCode").asText()).isEqualTo("57");

        JsonNode noAccount = authorize(client, NO_ACCOUNT, 1_000);
        assertThat(noAccount.path("responseCode").asText()).isEqualTo("14");
        assertThat(noAccount.path("id").isNull()).as("answered by the issuer, so stored").isFalse();
        assertThat(noAccount.path("maskedPan").asText()).isEqualTo("222300******0010");
    }

    @Test
    void aCardNumberOutsideTheTestCardsIsAnswered14AndLeavesNoTrace(CapturedOutput output) throws Exception {
        String masked = "541275******3452";
        long vault = count("SELECT count(*) FROM vault_record");
        long authorizations = count("SELECT count(*) FROM card_authorization");
        long audit = count("SELECT count(*) FROM audit_log");
        long outbox = count("SELECT count(*) FROM outbox");

        mvc.perform(from(newClient(), post("/v1/authorizations"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UNLISTED, 1_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("14"))
                .andExpect(jsonPath("$.id").isEmpty())
                .andExpect(jsonPath("$.maskedPan").isEmpty())
                .andExpect(jsonPath("$.token").isEmpty());

        assertThat(count("SELECT count(*) FROM vault_record")).isEqualTo(vault);
        assertThat(count("SELECT count(*) FROM card_authorization")).isEqualTo(authorizations);
        assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(audit);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(outbox);
        assertThat(count("SELECT count(*) FROM vault_record WHERE masked_pan = '" + masked + "'")).isZero();
        assertThat(count("SELECT count(*) FROM card_authorization WHERE masked_pan = '" + masked + "'")).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE details::text LIKE '%541275%'")).isZero();
        assertThat(output.getOut()).contains("answered 14 without storing it")
                .doesNotContain(UNLISTED)
                .doesNotContain(masked);
    }

    @Test
    void visitorsCanMakeTheIssuerFailWithinBounds() throws Exception {
        String client = newClient();
        mvc.perform(faults(client, "{\"latencyMs\": 60000, \"failureRate\": 0}")).andExpect(status().isBadRequest());
        mvc.perform(faults(client, "{\"latencyMs\": 0, \"failureRate\": 1.5}")).andExpect(status().isBadRequest());
        try {
            mvc.perform(faults(client, "{\"latencyMs\": 0, \"failureRate\": 1.0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failureRate").value(1.0))
                    .andExpect(jsonPath("$.resetsAt").isNotEmpty());
            String resetsAt = json.readTree(mvc.perform(from(client, get("/dev/issuer/faults")))
                    .andReturn().getResponse().getContentAsString()).path("resetsAt").asText();
            assertThat(Instant.parse(resetsAt))
                    .isBetween(Instant.now().plusSeconds(100), Instant.now().plusSeconds(121));

            JsonNode answer = authorize(client, FUNDED, 10_000);

            assertThat(answer.path("responseCode").asText()).isEqualTo("00");
            assertThat(answer.path("standIn").asBoolean()).isTrue();
        } finally {
            mvc.perform(from(client, delete("/dev/issuer/faults"))).andExpect(status().isOk());
            fixtures.issuerCircuitBreaker().reset();
        }
    }

    @Test
    void requestsThatChangeStateAreLimitedPerClientWithAProblemTheConsoleCanRead() throws Exception {
        String client = newClient() + ", 10.0.0.1";
        for (int i = 0; i < BURST; i++) {
            mvc.perform(from(client, post("/v1/authorizations/{id}/reversal", UUID.randomUUID())))
                    .andExpect(status().isNotFound());
        }

        mvc.perform(from(client, post("/v1/authorizations/{id}/reversal", UUID.randomUUID()))
                        .header(HttpHeaders.ORIGIN, CONSOLE))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too many requests"));

        mvc.perform(from(client, get("/v1/authorizations"))).andExpect(status().isOk());
        mvc.perform(from(newClient(), post("/v1/authorizations/{id}/reversal", UUID.randomUUID())))
                .andExpect(status().isNotFound());
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
        mvc.perform(options("/v1/authorizations")
                        .header(HttpHeaders.ORIGIN, CONSOLE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE));
        mvc.perform(options("/v1/authorizations")
                        .header(HttpHeaders.ORIGIN, "https://elsewhere.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void capturesReachClearingSignedOverHttpAndApprovalsAreNotSent() throws Exception {
        relay.publishPending();
        CLEARING.received.clear();
        String client = newClient();
        String id = authorize(client, FUNDED, 15_000).path("id").asText();
        mvc.perform(from(client, post("/v1/authorizations/{id}/capture", id))).andExpect(status().isOk());

        relay.publishPending();

        assertThat(CLEARING.received).singleElement().satisfies(event -> {
            assertThat(event.header("Cardrail-Event-Type")).isEqualTo("authorization.captured");
            assertThat(event.header("Cardrail-Event-Id")).matches("\\d+");
            assertThat(event.header("Cardrail-Signature"))
                    .isEqualTo(hmac(event.header("Cardrail-Timestamp") + "." + event.body()));
            assertThat(Long.parseLong(event.header("Cardrail-Timestamp")))
                    .isCloseTo(Instant.now().getEpochSecond(), offset(60L));
            JsonNode payload = json.readTree(event.body());
            assertThat(payload.path("eventType").asText()).isEqualTo("authorization.captured");
            assertThat(payload.path("authorizationId").asText()).isEqualTo(id);
            assertThat(payload.path("capturedAmountMinor").asLong()).isEqualTo(15_000);
        });
    }

    @Test
    void anEventClearingRefusesStaysInTheOutboxAndIsSentOnTheNextRun() throws Exception {
        relay.publishPending();
        CLEARING.received.clear();
        String client = newClient();
        String id = authorize(client, FUNDED, 5_000).path("id").asText();
        mvc.perform(from(client, post("/v1/authorizations/{id}/reversal", id))).andExpect(status().isOk());

        CLEARING.status.set(503);
        try {
            relay.publishPending();
        } finally {
            CLEARING.status.set(200);
        }

        Map<String, Object> row = fixtures.jdbc().sql("""
                        SELECT published_at, attempts, last_error FROM outbox
                         WHERE aggregate_id = :id AND event_type = 'authorization.reversed'
                        """)
                .param("id", id)
                .query()
                .singleRow();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("clearing answered 503");

        relay.publishPending();

        assertThat(CLEARING.received).hasSize(2)
                .allSatisfy(event -> assertThat(event.header("Cardrail-Event-Type")).isEqualTo("authorization.reversed"));
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = '" + id + "' AND published_at IS NULL"))
                .isZero();
    }

    private JsonNode authorize(String client, String pan, long amountMinor) throws Exception {
        return authorize(client, pan, amountMinor, "5411");
    }

    private JsonNode authorize(String client, String pan, long amountMinor, String mcc) throws Exception {
        String response = mvc.perform(from(client, post("/v1/authorizations"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(pan, amountMinor, mcc)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private MockHttpServletRequestBuilder faults(String client, String settings) {
        return from(client, put("/dev/issuer/faults")).contentType(MediaType.APPLICATION_JSON).content(settings);
    }

    /** Each test is its own client, so the rate limit of one does not spill into another. */
    private static String newClient() {
        return "198.51.100." + CLIENT.incrementAndGet();
    }

    private static MockHttpServletRequestBuilder from(String client, MockHttpServletRequestBuilder request) {
        return request.header("X-Forwarded-For", client);
    }

    private static String body(String pan, long amountMinor) {
        return body(pan, amountMinor, "5411");
    }

    /** As the console sends it. */
    private static String body(String pan, long amountMinor, String mcc) {
        int n = SEQUENCE.incrementAndGet();
        return """
                {"pan": "%s", "expiry": "3012", "amountMinor": %d, "currency": 356, "mcc": "%s",
                 "merchantId": "CRDEMO-GROCERY", "merchantName": "Fresh Mart Pune", "merchantCountry": "356",
                 "terminalId": "TERM0001", "acquirerId": "40001", "stan": "%06d", "rrn": "%012d",
                 "transmissionDateTime": "1017103000", "posEntryMode": "051"}
                """.formatted(pan, amountMinor, mcc, n, n);
    }

    private long count(String sql) {
        return fixtures.jdbc().sql(sql).query(Long.class).single();
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** Computed here rather than with the publisher's code, so a mistake on both sides cannot cancel out. */
    private static String hmac(String signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stands in for clearing's {@code POST /v1/events}: records each request and answers {@link #status}. */
    private static final class ClearingStub {

        record Received(Map<String, List<String>> headers, String body) {
            String header(String name) {
                return headers.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(e -> e.getValue().getFirst())
                        .findFirst()
                        .orElse(null);
            }
        }

        final List<Received> received = new CopyOnWriteArrayList<>();
        final AtomicInteger status = new AtomicInteger(200);
        private final HttpServer server;

        ClearingStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/v1/events", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(Map.copyOf(exchange.getRequestHeaders()), body));
                byte[] answer = "{\"outcome\":\"RECORDED\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }
}
