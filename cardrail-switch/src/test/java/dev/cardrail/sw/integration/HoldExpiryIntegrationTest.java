package dev.cardrail.sw.integration;

import dev.cardrail.sw.application.AbandonedAuthorizationSweeper;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.HoldExpiryJob;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.port.NewAuthorization;
import dev.cardrail.sw.port.TokenizedCard;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@CardrailIntegrationTest
class HoldExpiryIntegrationTest {

    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;
    @Autowired HoldExpiryJob expiry;
    @Autowired AbandonedAuthorizationSweeper sweeper;
    @Autowired AuthorizationRepository authorizations;
    @Autowired IssuerLedger ledger;

    @BeforeEach
    void reset() {
        fixtures.reset();
        fixtures.openAccount(PAN, 1_000_000);
    }

    @Test
    void holdsOlderThanTheExpiryWindowAreReleasedAndNewerOnesAreKept() {
        AuthorizationResult old = authorize.authorize(RequestBuilder.purchase(PAN, 20_000).build());
        AuthorizationResult recent = authorize.authorize(RequestBuilder.purchase(PAN, 5_000).build());
        age(old.id(), Duration.ofDays(8));

        assertThat(expiry.expireHolds()).isOne();

        StoredAuthorization expired = authorizations.findById(old.id()).orElseThrow();
        assertThat(expired.status()).isEqualTo(AuthorizationStatus.EXPIRED);
        assertThat(expired.expiredAt()).isNotNull();
        assertThat(authorizations.findById(recent.id()).orElseThrow().status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(5_000);
        assertThat(fixtures.journalCount(old.id(), "HOLD_EXPIRY")).isOne();
        assertThat(fixtures.outboxEventTypes(old.id())).contains("authorization.expired");
        assertThat(fixtures.unbalancedJournals()).isEmpty();
    }

    @Test
    void concurrentSweepersNeverReleaseTheSameHoldTwice() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add(authorize.authorize(RequestBuilder.purchase(PAN, 100).build()).id());
        }
        fixtures.jdbc().sql("UPDATE card_authorization SET created_at = created_at - interval '30 days'").update();

        List<Future<Integer>> runs = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 4; i++) {
                runs.add(executor.submit(expiry::expireHolds));
            }
        }
        int total = 0;
        for (Future<Integer> run : runs) {
            total += run.get();
        }

        assertThat(total).isEqualTo(250);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(1_000_000);
        assertThat(fixtures.jdbc().sql("SELECT count(*) FROM journal WHERE kind = 'HOLD_EXPIRY'")
                .query(Long.class).single()).isEqualTo(250);
    }

    @Test
    void abandonedPendingAuthorizationIsDeclined96AndItsHoldReleased() {
        UUID id = UUID.randomUUID();
        var request = RequestBuilder.purchase(PAN, 7_000).build();
        authorizations.insertIfAbsent(NewAuthorization.pending(id, request, "f".repeat(64),
                new TokenizedCard(fixtures.token(PAN), "555555******4444", "555555", "4444"),
                Instant.now()));
        ledger.placeHold(id, fixtures.token(PAN), 7_000, TestFixtures.INR);
        age(id, Duration.ofMinutes(10));

        assertThat(sweeper.sweep()).isOne();

        StoredAuthorization swept = authorizations.findById(id).orElseThrow();
        assertThat(swept.responseCode()).isEqualTo(ResponseCode.SYSTEM_MALFUNCTION);
        assertThat(swept.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
    }

    private void age(UUID id, Duration by) {
        fixtures.jdbc().sql("UPDATE card_authorization SET created_at = created_at - CAST(:by AS interval) WHERE id = :id")
                .param("by", by.toSeconds() + " seconds")
                .param("id", id)
                .update();
    }
}
