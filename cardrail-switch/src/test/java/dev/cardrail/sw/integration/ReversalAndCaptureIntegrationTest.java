package dev.cardrail.sw.integration;

import dev.cardrail.sw.application.AuthorizationConflictException;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.CaptureResult;
import dev.cardrail.sw.application.CaptureService;
import dev.cardrail.sw.application.InvalidCaptureAmountException;
import dev.cardrail.sw.application.ReversalResult;
import dev.cardrail.sw.application.ReverseService;
import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CardrailIntegrationTest
class ReversalAndCaptureIntegrationTest {

    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;
    @Autowired ReverseService reverse;
    @Autowired CaptureService capture;
    @Autowired AuthorizationRepository authorizations;

    @BeforeEach
    void reset() {
        fixtures.reset();
        fixtures.openAccount(PAN, 50_000);
    }

    @Test
    void reversalReleasesTheHoldOnceAndRepeatsReturnTheSameResult() {
        AuthorizationResult approved = authorize.authorize(RequestBuilder.purchase(PAN, 20_000).build());

        ReversalResult first = reverse.reverse(approved.id(), "test");
        ReversalResult second = reverse.reverse(approved.id(), "test");

        assertThat(first.outcome()).isEqualTo(ReversalResult.Outcome.REVERSED);
        assertThat(first.releasedMinor()).isEqualTo(20_000);
        assertThat(second).isEqualTo(first);
        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(50_000);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
        assertThat(fixtures.journalCount(approved.id(), "HOLD_RELEASE")).isOne();
        assertThat(fixtures.unbalancedJournals()).isEmpty();
        assertThat(fixtures.outboxEventTypes(approved.id()))
                .containsExactly("authorization.approved", "authorization.reversed");
    }

    @Test
    void reversalByOriginalDataElementsFindsTheAuthorization() {
        AuthorizationRequest request = RequestBuilder.purchase(PAN, 20_000).build();
        AuthorizationResult approved = authorize.authorize(request);

        ReversalResult result = reverse.reverse(request.transmissionKey(), "acquirer:40001");

        assertThat(result.authorizationId()).isEqualTo(approved.id());
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
    }

    @Test
    void reversingADeclineIsANoOp() {
        AuthorizationResult declined = authorize.authorize(RequestBuilder.purchase(PAN, 60_000).build());

        ReversalResult result = reverse.reverse(declined.id(), "test");

        assertThat(result.outcome()).isEqualTo(ReversalResult.Outcome.NOTHING_TO_REVERSE);
        assertThat(result.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(50_000);
        assertThat(fixtures.outboxEventTypes(declined.id())).containsExactly("authorization.declined");
    }

    @Test
    void partialCaptureSettlesPartAndReleasesTheRest() {
        AuthorizationResult approved = authorize.authorize(RequestBuilder.purchase(PAN, 20_000).build());

        CaptureResult result = capture.capture(approved.id(), 15_000L, "test");

        assertThat(result.capturedMinor()).isEqualTo(15_000);
        assertThat(result.releasedMinor()).isEqualTo(5_000);
        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(35_000);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
        assertThat(settlementPayable()).isEqualTo(15_000);
        assertThat(fixtures.unbalancedJournals()).isEmpty();

        StoredAuthorization stored = authorizations.findById(approved.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(AuthorizationStatus.CAPTURED);
        assertThat(stored.capturedAmountMinor()).isEqualTo(15_000);
        assertThat(stored.capturedAt()).isNotNull();
    }

    @Test
    void captureWithoutAmountTakesTheFullAuthorizationAndIsIdempotent() {
        AuthorizationResult approved = authorize.authorize(RequestBuilder.purchase(PAN, 20_000).build());

        CaptureResult first = capture.capture(approved.id(), null, "test");
        CaptureResult again = capture.capture(approved.id(), 20_000L, "test");

        assertThat(again).isEqualTo(first);
        assertThat(settlementPayable()).isEqualTo(20_000);
        assertThatThrownBy(() -> capture.capture(approved.id(), 10_000L, "test"))
                .isInstanceOf(AuthorizationConflictException.class);
    }

    @Test
    void captureCannotExceedTheAuthorizedAmount() {
        AuthorizationResult approved = authorize.authorize(RequestBuilder.purchase(PAN, 20_000).build());

        assertThatThrownBy(() -> capture.capture(approved.id(), 20_001L, "test"))
                .isInstanceOf(InvalidCaptureAmountException.class);
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(20_000);
    }

    @Test
    void capturedAuthorizationCannotBeReversedAndReversedCannotBeCaptured() {
        AuthorizationResult captured = authorize.authorize(RequestBuilder.purchase(PAN, 1_000).build());
        capture.capture(captured.id(), null, "test");
        AuthorizationResult reversed = authorize.authorize(RequestBuilder.purchase(PAN, 1_000).build());
        reverse.reverse(reversed.id(), "test");

        assertThatThrownBy(() -> reverse.reverse(captured.id(), "test")).isInstanceOf(AuthorizationConflictException.class);
        assertThatThrownBy(() -> capture.capture(reversed.id(), null, "test")).isInstanceOf(AuthorizationConflictException.class);
    }

    private long settlementPayable() {
        return fixtures.jdbc().sql("""
                        SELECT COALESCE(sum(e.amount_minor), 0) FROM journal_entry e
                          JOIN ledger_account a ON a.id = e.ledger_account_id
                         WHERE a.kind = 'SETTLEMENT_PAYABLE'
                        """)
                .query(Long.class)
                .single();
    }
}
