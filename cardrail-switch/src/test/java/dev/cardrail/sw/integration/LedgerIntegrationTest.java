package dev.cardrail.sw.integration;

import dev.cardrail.sw.domain.ledger.JournalKind;
import dev.cardrail.sw.port.HoldPlacement;
import dev.cardrail.sw.port.IssuerLedger;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CardrailIntegrationTest
class LedgerIntegrationTest {

    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired IssuerLedger ledger;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void unbalancedJournalIsAcceptedStatementByStatementButRejectedAtCommit() {
        fixtures.openAccount(PAN, 10_000);
        long account = jdbc.sql("SELECT available_ledger_account_id FROM issuer_account").query(Long.class).single();
        UUID journal = UUID.randomUUID();
        AtomicBoolean insertSucceeded = new AtomicBoolean();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO journal (id, kind) VALUES (:id, 'HOLD')").param("id", journal).update();
            jdbc.sql("INSERT INTO journal_entry (journal_id, ledger_account_id, amount_minor) VALUES (:j, :a, 500)")
                    .param("j", journal).param("a", account).update();
            insertSucceeded.set(true);
        }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("is unbalanced: entries sum to 500");

        assertThat(insertSucceeded).as("the check is deferred to commit, not per statement").isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM journal WHERE id = :id").param("id", journal)
                .query(Long.class).single()).isZero();
    }

    @Test
    void postedEntriesCannotBeChanged() {
        fixtures.openAccount(PAN, 10_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entry SET amount_minor = amount_minor * 2").update())
                .rootCause().hasMessageContaining("journal_entry is append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal").update())
                .rootCause().hasMessageContaining("journal is append-only");
    }

    @Test
    void holdMovesAvailableToHeldWithABalancedJournal() {
        fixtures.openAccount(PAN, 10_000);
        UUID authorization = UUID.randomUUID();

        assertThat(ledger.placeHold(authorization, fixtures.token(PAN), 2_500, TestFixtures.INR))
                .isEqualTo(HoldPlacement.PLACED);

        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(7_500);
        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(2_500);
        assertThat(fixtures.heldLedgerBalance(PAN)).isEqualTo(2_500);
        assertThat(fixtures.availableLedgerBalance(PAN)).isEqualTo(7_500);
        assertThat(fixtures.journalCount(authorization, "HOLD")).isOne();
        assertThat(fixtures.unbalancedJournals()).isEmpty();
    }

    @Test
    void repeatedHoldForTheSameAuthorizationIsPlacedOnce() {
        fixtures.openAccount(PAN, 10_000);
        UUID authorization = UUID.randomUUID();

        ledger.placeHold(authorization, fixtures.token(PAN), 2_500, TestFixtures.INR);
        assertThat(ledger.placeHold(authorization, fixtures.token(PAN), 2_500, TestFixtures.INR))
                .isEqualTo(HoldPlacement.PLACED);

        assertThat(fixtures.balance(PAN).heldMinor()).isEqualTo(2_500);
    }

    @Test
    void voidBeforeTheIssuerAnswersPreventsTheHold() {
        fixtures.openAccount(PAN, 10_000);
        UUID authorization = UUID.randomUUID();

        Boolean released = transactions.execute(status -> ledger.voidHold(authorization));
        assertThat(released).isFalse();
        assertThat(ledger.placeHold(authorization, fixtures.token(PAN), 2_500, TestFixtures.INR))
                .isEqualTo(HoldPlacement.ALREADY_VOIDED);

        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(10_000);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
    }

    @Test
    void voidAfterALateIssuerApprovalReleasesTheHold() {
        fixtures.openAccount(PAN, 10_000);
        UUID authorization = UUID.randomUUID();
        ledger.placeHold(authorization, fixtures.token(PAN), 2_500, TestFixtures.INR);

        Boolean released = transactions.execute(status -> ledger.voidHold(authorization));
        assertThat(released).isTrue();

        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(10_000);
        assertThat(fixtures.balance(PAN).heldMinor()).isZero();
        assertThat(fixtures.journalCount(authorization, JournalKind.HOLD_RELEASE.name())).isOne();
        assertThat(fixtures.unbalancedJournals()).isEmpty();
    }

    @Test
    void holdIsRefusedWhenFundsAreShortAndTheAccountIsUntouched() {
        fixtures.openAccount(PAN, 1_000);

        assertThat(ledger.placeHold(UUID.randomUUID(), fixtures.token(PAN), 1_001, TestFixtures.INR))
                .isEqualTo(HoldPlacement.INSUFFICIENT_FUNDS);
        assertThat(fixtures.balance(PAN).availableMinor()).isEqualTo(1_000);
    }

    @Test
    void databaseRejectsANegativeAvailableBalanceEvenIfTheApplicationCheckWereBypassed() {
        fixtures.openAccount(PAN, 1_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE issuer_account SET available_minor = available_minor - 1001").update())
                .rootCause().hasMessageContaining("issuer_account_available_minor_check");
    }
}
