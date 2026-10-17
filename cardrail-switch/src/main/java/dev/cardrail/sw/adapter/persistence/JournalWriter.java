package dev.cardrail.sw.adapter.persistence;

import dev.cardrail.sw.domain.ledger.Journal;
import dev.cardrail.sw.domain.ledger.Posting;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JournalWriter {

    private final JdbcClient jdbc;

    JournalWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside a transaction: the balance check fires at commit. */
    void post(Journal journal) {
        jdbc.sql("INSERT INTO journal (id, kind, authorization_id) VALUES (:id, :kind, :authorizationId)")
                .param("id", journal.id())
                .param("kind", journal.kind().name())
                .param("authorizationId", journal.authorizationId())
                .update();
        for (Posting posting : journal.postings()) {
            jdbc.sql("""
                            INSERT INTO journal_entry (journal_id, ledger_account_id, amount_minor)
                            VALUES (:journalId, :account, :amount)
                            """)
                    .param("journalId", journal.id())
                    .param("account", posting.ledgerAccountId())
                    .param("amount", posting.amountMinor())
                    .update();
        }
    }

    /** Returns the id of the ledger account with this ref, creating it on first use. */
    long ledgerAccount(String accountRef, String kind, int currency) {
        jdbc.sql("""
                        INSERT INTO ledger_account (account_ref, kind, currency) VALUES (:ref, :kind, :currency)
                        ON CONFLICT (account_ref) DO NOTHING
                        """)
                .param("ref", accountRef)
                .param("kind", kind)
                .param("currency", currency)
                .update();
        return jdbc.sql("SELECT id FROM ledger_account WHERE account_ref = :ref")
                .param("ref", accountRef)
                .query(Long.class)
                .single();
    }
}
