package dev.cardrail.sw.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cardrail.sw.adapter.issuer.SimulatedIssuerFaults;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@CardrailIntegrationTest
class AuthorizationSearchIntegrationTest {

    private static final String RICH = "5555555555554444";
    private static final String POOR = "5105105105105100";
    private static final String CONSOLE = "http://localhost:4200";

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;
    @Autowired AuthorizationRepository authorizations;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    /** Oldest first: approved, approved, 51, approved in stand-in, 14. */
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void seed() {
        fixtures.reset();
        fixtures.openAccount(RICH, 5_000_000);
        fixtures.openAccount(POOR, 50_000);
        created.clear();
        created.add(authorized(RequestBuilder.purchase(RICH, 1_000).merchantId("MERCHANT0000001")));
        created.add(authorized(RequestBuilder.purchase(RICH, 2_000).merchantId("MERCHANT0000002")));
        created.add(authorized(RequestBuilder.purchase(POOR, 60_000).merchantId("MERCHANT0000001")));
        fixtures.faults().set(new SimulatedIssuerFaults.Settings(Duration.ZERO, 1.0));
        created.add(authorized(RequestBuilder.purchase(POOR, 1_000).merchantId("MERCHANT0000001")));
        fixtures.faults().reset();
        created.add(authorized(RequestBuilder.purchase("5555555555554445", 1_000).merchantId("MERCHANT0000002")));
    }

    @Test
    void unfilteredIsNewestFirstWithTheTotal() throws Exception {
        JsonNode page = search(get("/v1/authorizations"));

        assertThat(ids(page)).containsExactly(id(4), id(3), id(2), id(1), id(0));
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("total").asLong()).isEqualTo(5);
        assertThat(page.toString()).doesNotContain(RICH).doesNotContain(POOR).doesNotContain("5555555555554445");
        assertThat(page.get("items").get(1).get("maskedPan").asText()).isEqualTo("510510******5100");
    }

    @Test
    void eachFilterNarrowsTheResult() throws Exception {
        assertThat(ids(search(get("/v1/authorizations").param("status", "DECLINED")))).containsExactly(id(4), id(2));
        assertThat(ids(search(get("/v1/authorizations").param("responseCode", "51")))).containsExactly(id(2));
        assertThat(ids(search(get("/v1/authorizations").param("merchantId", "MERCHANT0000002"))))
                .containsExactly(id(4), id(1));
        assertThat(ids(search(get("/v1/authorizations").param("last4", "5100")))).containsExactly(id(3), id(2));
        assertThat(ids(search(get("/v1/authorizations").param("standIn", "true")))).containsExactly(id(3));
        assertThat(ids(search(get("/v1/authorizations").param("standIn", "false"))))
                .containsExactly(id(4), id(2), id(1), id(0));
    }

    @Test
    void filtersCombineAndTheTimeRangeIsHalfOpen() throws Exception {
        Instant second = createdAt(1);
        Instant fourth = createdAt(3);

        JsonNode range = search(get("/v1/authorizations")
                .param("from", second.toString()).param("to", fourth.toString()));
        assertThat(ids(range)).containsExactly(id(2), id(1));

        JsonNode combined = search(get("/v1/authorizations")
                .param("merchantId", "MERCHANT0000001").param("status", "AUTHORIZED").param("last4", "4444"));
        assertThat(ids(combined)).containsExactly(id(0));
        assertThat(combined.get("total").asLong()).isOne();
    }

    @Test
    void pagesWalkTheWholeResultWithAStableTotal() throws Exception {
        assertThat(ids(search(get("/v1/authorizations").param("size", "2")))).containsExactly(id(4), id(3));
        assertThat(ids(search(get("/v1/authorizations").param("size", "2").param("page", "1"))))
                .containsExactly(id(2), id(1));
        JsonNode last = search(get("/v1/authorizations").param("size", "2").param("page", "2"));
        assertThat(ids(last)).containsExactly(id(0));
        assertThat(last.get("total").asLong()).isEqualTo(5);

        JsonNode beyond = search(get("/v1/authorizations").param("size", "2").param("page", "3"));
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.get("total").asLong()).isEqualTo(5);
        assertThat(beyond.get("page").asInt()).isEqualTo(3);
    }

    @Test
    void outOfRangeOrMalformedParametersAre400ProblemDetails() throws Exception {
        for (MockHttpServletRequestBuilder bad : List.of(
                get("/v1/authorizations").param("size", "101"),
                get("/v1/authorizations").param("size", "0"),
                get("/v1/authorizations").param("page", "-1"),
                get("/v1/authorizations").param("last4", "44"),
                get("/v1/authorizations").param("responseCode", "XX"),
                get("/v1/authorizations").param("status", "SETTLED"),
                get("/v1/authorizations").param("from", "yesterday"),
                get("/v1/authorizations").param("from", "2026-10-18T10:00:00Z").param("to", "2026-10-18T09:00:00Z"))) {
            mvc.perform(bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/problem+json"));
        }
    }

    @Test
    void theSelectiveFiltersAreServedByTheirIndexes() {
        // With sequential scans priced out, the planner shows which index can answer each query shape.
        assertThat(plan("merchant_id = 'MERCHANT0000001'")).contains("card_authorization_merchant_idx");
        assertThat(plan("right(masked_pan, 4) = '4444'")).contains("card_authorization_last4_idx");
        assertThat(plan("created_at >= now() - interval '1 day'")).contains("card_authorization_created_at_idx");
    }

    @Test
    void theConsoleOriginMayCallTheApiAndOthersMayNot() throws Exception {
        mvc.perform(options("/v1/authorizations")
                        .header(HttpHeaders.ORIGIN, CONSOLE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

        mvc.perform(get("/v1/authorizations").header(HttpHeaders.ORIGIN, CONSOLE))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Correlation-Id, Retry-After"));

        mvc.perform(options("/v1/authorizations")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/authorizations").header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(status().isForbidden());
    }

    private UUID authorized(RequestBuilder request) {
        AuthorizationResult result = authorize.authorize(request.build());
        return result.id();
    }

    private String id(int index) {
        return created.get(index).toString();
    }

    private Instant createdAt(int index) {
        return authorizations.findById(created.get(index)).orElseThrow().createdAt();
    }

    private JsonNode search(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private String plan(String condition) {
        return new TransactionTemplate(transactions).execute(status -> {
            fixtures.jdbc().sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", fixtures.jdbc().sql("EXPLAIN SELECT id FROM card_authorization WHERE "
                            + condition + " ORDER BY created_at DESC, id DESC LIMIT 20")
                    .query(String.class).list());
        });
    }
}
