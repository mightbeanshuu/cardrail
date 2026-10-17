package dev.cardrail.sw.adapter.persistence;

import dev.cardrail.sw.domain.ledger.Journal;
import dev.cardrail.sw.domain.ledger.JournalKind;
import dev.cardrail.sw.port.AccountBalance;
import dev.cardrail.sw.port.AccountStatus;
import dev.cardrail.sw.port.HoldPlacement;
import dev.cardrail.sw.port.IssuerLedger;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Lock order, everywhere: issuer_hold row, then issuer_account row. {@link #placeHold} locks the
 * account first, but the hold row it inserts is new and can only be contended by {@link #voidHold}
 * for the same authorization, which never holds an account lock while waiting for it.
 */
@Repository
@Transactional
class JdbcIssuerLedger implements IssuerLedger {

    private final JdbcClient jdbc;
    private final JournalWriter journals;

    JdbcIssuerLedger(JdbcClient jdbc, JournalWriter journals) {
        this.jdbc = jdbc;
        this.journals = journals;
    }

    @Override
    public void openAccount(String token, int currency, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance must not be negative");
        }
        long available = journals.ledgerAccount("cardholder:" + token + ":available", "CARDHOLDER_AVAILABLE", currency);
        long held = journals.ledgerAccount("cardholder:" + token + ":held", "CARDHOLDER_HELD", currency);
        long funding = journals.ledgerAccount("issuer-funding:" + currency, "ISSUER_FUNDING", currency);
        jdbc.sql("""
                        INSERT INTO issuer_account (token, currency, available_minor, available_ledger_account_id,
                                                    held_ledger_account_id)
                        VALUES (:token, :currency, :available, :availableAccount, :heldAccount)
                        """)
                .param("token", token)
                .param("currency", currency)
                .param("available", openingBalanceMinor)
                .param("availableAccount", available)
                .param("heldAccount", held)
                .update();
        if (openingBalanceMinor > 0) {
            journals.post(Journal.transfer(JournalKind.OPENING_BALANCE, null, funding, available, openingBalanceMinor));
        }
    }

    @Override
    public void setStatus(String token, AccountStatus status) {
        int updated = jdbc.sql("""
                        UPDATE issuer_account SET status = :status, version = version + 1, updated_at = now()
                         WHERE token = :token
                        """)
                .param("status", status.name())
                .param("token", token)
                .update();
        if (updated != 1) {
            throw new IllegalArgumentException("no issuer account for token");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountBalance> balance(String token) {
        return jdbc.sql("SELECT token, currency, available_minor, held_minor, status FROM issuer_account WHERE token = :token")
                .param("token", token)
                .query((rs, n) -> new AccountBalance(rs.getString("token"), rs.getInt("currency"),
                        rs.getLong("available_minor"), rs.getLong("held_minor"),
                        AccountStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    @Override
    public HoldPlacement placeHold(UUID authorizationId, String token, long amountMinor, int currency) {
        Optional<LockedAccount> found = jdbc.sql("""
                        SELECT id, currency, available_minor, status, available_ledger_account_id, held_ledger_account_id
                          FROM issuer_account WHERE token = :token FOR UPDATE
                        """)
                .param("token", token)
                .query(LockedAccount.MAPPER)
                .optional();
        if (found.isEmpty()) {
            return HoldPlacement.NO_ACCOUNT;
        }
        LockedAccount account = found.get();
        Optional<String> existing = jdbc.sql("SELECT status FROM issuer_hold WHERE authorization_id = :id")
                .param("id", authorizationId)
                .query(String.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get().equals("VOIDED") ? HoldPlacement.ALREADY_VOIDED : HoldPlacement.PLACED;
        }
        if (!account.status().equals("ACTIVE")) {
            return HoldPlacement.ACCOUNT_BLOCKED;
        }
        if (account.currency() != currency) {
            return HoldPlacement.CURRENCY_MISMATCH;
        }
        if (account.availableMinor() < amountMinor) {
            return HoldPlacement.INSUFFICIENT_FUNDS;
        }
        int inserted = jdbc.sql("""
                        INSERT INTO issuer_hold (authorization_id, issuer_account_id, amount_minor, status)
                        VALUES (:id, :account, :amount, 'HELD')
                        ON CONFLICT (authorization_id) DO NOTHING
                        """)
                .param("id", authorizationId)
                .param("account", account.id())
                .param("amount", amountMinor)
                .update();
        if (inserted == 0) {
            return HoldPlacement.ALREADY_VOIDED;
        }
        adjustBalances(account.id(), -amountMinor, amountMinor);
        journals.post(Journal.transfer(JournalKind.HOLD, authorizationId,
                account.availableLedgerAccountId(), account.heldLedgerAccountId(), amountMinor));
        return HoldPlacement.PLACED;
    }

    @Override
    public boolean voidHold(UUID authorizationId) {
        // If the issuer's transaction is still in flight, this insert waits for it to finish: either
        // it committed a hold (conflict, and we release it) or it will find our tombstone and back off.
        int inserted = jdbc.sql("""
                        INSERT INTO issuer_hold (authorization_id, issuer_account_id, amount_minor, status)
                        VALUES (:id, NULL, 0, 'VOIDED')
                        ON CONFLICT (authorization_id) DO NOTHING
                        """)
                .param("id", authorizationId)
                .update();
        return inserted == 0 && releaseHold(authorizationId, JournalKind.HOLD_RELEASE) > 0;
    }

    @Override
    public long releaseHold(UUID authorizationId, JournalKind kind) {
        if (kind != JournalKind.HOLD_RELEASE && kind != JournalKind.HOLD_EXPIRY) {
            throw new IllegalArgumentException("not a release journal kind: " + kind);
        }
        Optional<LockedHold> hold = lockActiveHold(authorizationId);
        if (hold.isEmpty()) {
            return 0;
        }
        LockedAccount account = lockAccount(hold.get().accountId());
        long amount = hold.get().amountMinor();
        adjustBalances(account.id(), amount, -amount);
        journals.post(Journal.transfer(kind, authorizationId,
                account.heldLedgerAccountId(), account.availableLedgerAccountId(), amount));
        setHoldStatus(authorizationId, "RELEASED");
        return amount;
    }

    @Override
    public void captureHold(UUID authorizationId, long captureAmountMinor, String settlementAccountRef) {
        LockedHold hold = lockActiveHold(authorizationId)
                .orElseThrow(() -> new IllegalStateException("no active hold for authorization " + authorizationId));
        if (captureAmountMinor <= 0 || captureAmountMinor > hold.amountMinor()) {
            throw new IllegalArgumentException("capture amount must be in (0, " + hold.amountMinor() + "]");
        }
        LockedAccount account = lockAccount(hold.accountId());
        long settlement = journals.ledgerAccount(settlementAccountRef, "SETTLEMENT_PAYABLE", account.currency());
        long remainder = hold.amountMinor() - captureAmountMinor;
        adjustBalances(account.id(), remainder, -hold.amountMinor());
        journals.post(Journal.builder(JournalKind.CAPTURE, authorizationId)
                .move(account.heldLedgerAccountId(), settlement, captureAmountMinor)
                .move(account.heldLedgerAccountId(), account.availableLedgerAccountId(), remainder)
                .build());
        setHoldStatus(authorizationId, "CAPTURED");
    }

    private Optional<LockedHold> lockActiveHold(UUID authorizationId) {
        return jdbc.sql("""
                        SELECT issuer_account_id, amount_minor FROM issuer_hold
                         WHERE authorization_id = :id AND status = 'HELD'
                           FOR UPDATE
                        """)
                .param("id", authorizationId)
                .query((rs, n) -> new LockedHold(rs.getLong("issuer_account_id"), rs.getLong("amount_minor")))
                .optional();
    }

    private LockedAccount lockAccount(long accountId) {
        return jdbc.sql("""
                        SELECT id, currency, available_minor, status, available_ledger_account_id, held_ledger_account_id
                          FROM issuer_account WHERE id = :id FOR UPDATE
                        """)
                .param("id", accountId)
                .query(LockedAccount.MAPPER)
                .single();
    }

    private void adjustBalances(long accountId, long availableDelta, long heldDelta) {
        jdbc.sql("""
                        UPDATE issuer_account
                           SET available_minor = available_minor + :availableDelta,
                               held_minor = held_minor + :heldDelta,
                               version = version + 1,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("availableDelta", availableDelta)
                .param("heldDelta", heldDelta)
                .param("id", accountId)
                .update();
    }

    private void setHoldStatus(UUID authorizationId, String status) {
        jdbc.sql("UPDATE issuer_hold SET status = :status, updated_at = now() WHERE authorization_id = :id")
                .param("status", status)
                .param("id", authorizationId)
                .update();
    }

    private record LockedHold(long accountId, long amountMinor) {
    }

    private record LockedAccount(long id, int currency, long availableMinor, String status,
                                 long availableLedgerAccountId, long heldLedgerAccountId) {

        static final RowMapper<LockedAccount> MAPPER = (rs, n) -> new LockedAccount(
                rs.getLong("id"), rs.getInt("currency"), rs.getLong("available_minor"), rs.getString("status"),
                rs.getLong("available_ledger_account_id"), rs.getLong("held_ledger_account_id"));
    }
}
