package dev.cardrail.sw.support;

import dev.cardrail.sw.adapter.issuer.SimulatedIssuerFaults;
import dev.cardrail.sw.port.AccountBalance;
import dev.cardrail.sw.port.CardTokenizer;
import dev.cardrail.sw.port.IssuerLedger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

public class TestFixtures {

    public static final int INR = 356;

    @Autowired private JdbcClient jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private StringRedisTemplate redis;
    @Autowired private CardTokenizer tokenizer;
    @Autowired private IssuerLedger ledger;
    @Autowired private CircuitBreaker issuerCircuitBreaker;
    @Autowired private SimulatedIssuerFaults faults;

    /**
     * Empties every table (TRUNCATE bypasses the append-only row triggers), reseeds reference data.
     * The vault is emptied too, so a PAN gets a new token in each test; accounts are opened after this.
     */
    public void reset() {
        jdbc.sql("""
                TRUNCATE card_authorization, issuer_hold, issuer_account, journal_entry, journal, ledger_account,
                         outbox, audit_log, stand_in_limit, mcc_rule, vault_record RESTART IDENTITY CASCADE
                """).update();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__seed_reference_data.sql"))
                .execute(dataSource);
        redis.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
        issuerCircuitBreaker.reset();
        faults.reset();
    }

    public String token(String pan) {
        return tokenizer.tokenize(pan).token();
    }

    public String openAccount(String pan, long balanceMinor) {
        String token = token(pan);
        ledger.openAccount(token, INR, balanceMinor);
        return token;
    }

    public AccountBalance balance(String pan) {
        return ledger.balance(token(pan)).orElseThrow();
    }

    /** Journals whose entries do not sum to zero; must always be empty. */
    public List<UUID> unbalancedJournals() {
        return jdbc.sql("SELECT journal_id FROM journal_entry GROUP BY journal_id HAVING sum(amount_minor) <> 0")
                .query(UUID.class)
                .list();
    }

    /** Sum of entries on the card's held ledger account; must equal issuer_account.held_minor. */
    public long heldLedgerBalance(String pan) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(e.amount_minor), 0) FROM journal_entry e
                          JOIN issuer_account a ON a.held_ledger_account_id = e.ledger_account_id
                         WHERE a.token = :token
                        """)
                .param("token", token(pan))
                .query(Long.class)
                .single();
    }

    public long availableLedgerBalance(String pan) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(e.amount_minor), 0) FROM journal_entry e
                          JOIN issuer_account a ON a.available_ledger_account_id = e.ledger_account_id
                         WHERE a.token = :token
                        """)
                .param("token", token(pan))
                .query(Long.class)
                .single();
    }

    public List<String> outboxEventTypes(UUID authorizationId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY id")
                .param("id", authorizationId.toString())
                .query(String.class)
                .list();
    }

    public long journalCount(UUID authorizationId, String kind) {
        return jdbc.sql("SELECT count(*) FROM journal WHERE authorization_id = :id AND kind = :kind")
                .param("id", authorizationId)
                .param("kind", kind)
                .query(Long.class)
                .single();
    }

    public SimulatedIssuerFaults faults() {
        return faults;
    }

    public CircuitBreaker issuerCircuitBreaker() {
        return issuerCircuitBreaker;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }
}
