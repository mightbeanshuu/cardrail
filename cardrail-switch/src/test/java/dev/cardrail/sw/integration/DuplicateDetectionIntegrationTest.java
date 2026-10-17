package dev.cardrail.sw.integration;

import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@CardrailIntegrationTest
class DuplicateDetectionIntegrationTest {

    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void retransmissionReplaysTheOriginalResponseAndHoldsOnlyOnce() {
        fixtures.openAccount(PAN, 50_000);
        AuthorizationRequest request = RequestBuilder.purchase(PAN, 20_000).build();

        AuthorizationResult first = authorize.authorize(request);
        AuthorizationResult second = authorize.authorize(request);

        assertThat(second).isEqualTo(first);
        assertThat(second.authCode()).isEqualTo(first.authCode());
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(20_000);
        assertThat(rows()).isOne();
    }

    @Test
    void sameTransmissionKeyWithDifferentContentIs94() {
        fixtures.openAccount(PAN, 50_000);
        RequestBuilder original = RequestBuilder.purchase(PAN, 20_000);
        AuthorizationResult first = authorize.authorize(original.build());

        AuthorizationResult reused = authorize.authorize(original.amountMinor(30_000).build());

        assertThat(first.responseCode()).isEqualTo(ResponseCode.APPROVED);
        assertThat(reused.responseCode()).isEqualTo(ResponseCode.DUPLICATE_TRANSMISSION);
        assertThat(reused.id()).isNull();
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(20_000);
        assertThat(rows()).isOne();
    }

    @Test
    void declinesAreReplayedToo() {
        fixtures.openAccount(PAN, 100);
        AuthorizationRequest request = RequestBuilder.purchase(PAN, 20_000).build();

        assertThat(authorize.authorize(request).responseCode()).isEqualTo(ResponseCode.INSUFFICIENT_FUNDS);
        assertThat(authorize.authorize(request).responseCode()).isEqualTo(ResponseCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void concurrentCopiesOfOneTransmissionProduceOneAuthorizationAndOneAnswer() throws Exception {
        fixtures.openAccount(PAN, 50_000);
        AuthorizationRequest request = RequestBuilder.purchase(PAN, 20_000).build();

        List<Future<AuthorizationResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> authorize.authorize(request)));
            }
        }
        List<AuthorizationResult> results = new ArrayList<>();
        for (Future<AuthorizationResult> f : futures) {
            results.add(f.get());
        }

        assertThat(results).allSatisfy(r -> assertThat(r).isEqualTo(results.getFirst()));
        assertThat(results.getFirst().responseCode()).isEqualTo(ResponseCode.APPROVED);
        assertThat(rows()).isOne();
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(20_000);
    }

    private long rows() {
        return fixtures.jdbc().sql("SELECT count(*) FROM card_authorization").query(Long.class).single();
    }
}
