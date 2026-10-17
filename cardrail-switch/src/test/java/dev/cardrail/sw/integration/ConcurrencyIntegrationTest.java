package dev.cardrail.sw.integration;

import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@CardrailIntegrationTest
class ConcurrencyIntegrationTest {

    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void twoHundredConcurrentAuthorizationsNeverOverdrawTheAccount() throws Exception {
        fixtures.openAccount(PAN, 10_000);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<AuthorizationResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                RequestBuilder request = RequestBuilder.purchase(PAN, 100);
                futures.add(executor.submit(() -> {
                    start.await();
                    return authorize.authorize(request.build());
                }));
            }
            start.countDown();
        }
        List<AuthorizationResult> results = new ArrayList<>();
        for (Future<AuthorizationResult> f : futures) {
            results.add(f.get());
        }

        Map<ResponseCode, Long> byCode = results.stream()
                .collect(Collectors.groupingBy(AuthorizationResult::responseCode, Collectors.counting()));
        assertThat(byCode).containsExactlyInAnyOrderEntriesOf(Map.of(
                ResponseCode.APPROVED, 100L,
                ResponseCode.INSUFFICIENT_FUNDS, 100L));
        assertThat(results).noneMatch(AuthorizationResult::standIn);
        assertThat(fixtures.balance(PAN).availableMinor()).isZero();
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(10_000);
        assertThat(fixtures.heldLedgerBalance(PAN)).isEqualTo(10_000);
        assertThat(fixtures.availableLedgerBalance(PAN)).isZero();
        assertThat(fixtures.unbalancedJournals()).isEmpty();
        assertThat(fixtures.jdbc().sql("SELECT count(*) FROM journal WHERE kind = 'HOLD'").query(Long.class).single())
                .isEqualTo(100);
        assertThat(results.stream().map(AuthorizationResult::id).collect(Collectors.toSet())).hasSize(200);
        assertThat(results.stream().filter(AuthorizationResult::approved).map(AuthorizationResult::authCode)
                .collect(Collectors.toMap(Function.identity(), c -> 1, Integer::sum)).values())
                .as("auth codes are random, not sequential; collisions are possible but must be rare")
                .allMatch(n -> n <= 2);
    }
}
