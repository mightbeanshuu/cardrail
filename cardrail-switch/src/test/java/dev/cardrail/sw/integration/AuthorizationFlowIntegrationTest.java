package dev.cardrail.sw.integration;

import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.port.AccountStatus;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@CardrailIntegrationTest
class AuthorizationFlowIntegrationTest {

    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;
    @Autowired AuthorizationRepository authorizations;
    @Autowired IssuerLedger ledger;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void approvalPlacesTheHoldAndRecordsEverythingInOneGo() {
        fixtures.openAccount(PAN, 50_000);

        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 20_000).build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.APPROVED);
        assertThat(result.authCode()).hasSize(6);
        assertThat(result.standIn()).isFalse();
        assertThat(result.maskedPan()).isEqualTo("555555******4444");
        assertThat(result.token()).isEqualTo(fixtures.token(PAN));

        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(30_000);
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(20_000);
        assertThat(fixtures.heldLedgerBalance(PAN)).isEqualTo(20_000);
        assertThat(fixtures.journalCount(result.id(), "HOLD")).isOne();
        assertThat(fixtures.unbalancedJournals()).isEmpty();

        StoredAuthorization stored = authorizations.findById(result.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        assertThat(stored.authCode()).isEqualTo(result.authCode());
        assertThat(fixtures.outboxEventTypes(result.id())).containsExactly("authorization.approved");
        assertThat(fixtures.jdbc().sql("SELECT count(*) FROM audit_log WHERE entity_id = :id")
                .param("id", result.id().toString()).query(Long.class).single()).isOne();
    }

    @Test
    void insufficientFundsIs51AndLeavesTheAccountUntouched() {
        fixtures.openAccount(PAN, 10_000);

        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 10_001).build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.INSUFFICIENT_FUNDS);
        assertThat(result.authCode()).isNull();
        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(10_000);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
        assertThat(authorizations.findById(result.id()).orElseThrow().status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(fixtures.outboxEventTypes(result.id())).containsExactly("authorization.declined");
    }

    @Test
    void blockedAccountIs05() {
        fixtures.openAccount(PAN, 10_000);
        ledger.setStatus(fixtures.token(PAN), AccountStatus.BLOCKED);

        assertThat(authorize.authorize(RequestBuilder.purchase(PAN, 100).build()).responseCode())
                .isEqualTo(ResponseCode.DO_NOT_HONOR);
    }

    @Test
    void blockedMerchantCategoryIs57AndNeverReachesTheIssuer() {
        fixtures.openAccount(PAN, 10_000);

        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 100).mcc("7995").build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.NOT_PERMITTED_TO_CARDHOLDER);
        assertThat(authorizations.findById(result.id()).orElseThrow().declineReason()).isEqualTo("mcc-blocked");
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
    }

    @Test
    void amountOverThePerTransactionLimitIs61() {
        fixtures.openAccount(PAN, 100_000_000);

        assertThat(authorize.authorize(RequestBuilder.purchase(PAN, 20_000_001).build()).responseCode())
                .isEqualTo(ResponseCode.EXCEEDS_AMOUNT_LIMIT);
    }

    @Test
    void expiredCardIs54AndIsStored() {
        fixtures.openAccount(PAN, 10_000);

        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 100).expiry("2001").build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.EXPIRED_CARD);
        assertThat(authorizations.findById(result.id()).orElseThrow().token()).isEqualTo(fixtures.token(PAN));
    }

    @Test
    void invalidCardNumberIs14AndOnlyTheMaskedFormIsStored() {
        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase("5555555555554445", 100).build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.INVALID_CARD_NUMBER);
        StoredAuthorization stored = authorizations.findById(result.id()).orElseThrow();
        assertThat(stored.token()).isNull();
        assertThat(stored.maskedPan()).isEqualTo("555555******4445");
        assertThat(fixtures.jdbc().sql("SELECT count(*) FROM card_authorization WHERE row_to_json(card_authorization)::text LIKE '%5555555555554445%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void formatErrorIs30AndIsNotStored() {
        AuthorizationResult result = authorize.authorize(RequestBuilder.purchase(PAN, 100).stan("12").build());

        assertThat(result.responseCode()).isEqualTo(ResponseCode.FORMAT_ERROR);
        assertThat(result.id()).isNull();
        assertThat(fixtures.jdbc().sql("SELECT count(*) FROM card_authorization").query(Long.class).single()).isZero();
    }

    @Test
    void unknownCardAtTheIssuerIs14() {
        assertThat(authorize.authorize(RequestBuilder.purchase(PAN, 100).build()).responseCode())
                .isEqualTo(ResponseCode.INVALID_CARD_NUMBER);
    }
}
