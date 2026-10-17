package dev.cardrail.sw.integration;

import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.audit.ChainVerification;
import dev.cardrail.sw.port.AuditTrail;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CardrailIntegrationTest
class AuditLogIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired AuditTrail audit;
    @Autowired AuthorizeService authorize;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void auditRowsCannotBeUpdatedOrDeleted() {
        append(1);

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET actor = 'someone-else'").update())
                .rootCause().hasMessageContaining("audit_log is append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log").update())
                .rootCause().hasMessageContaining("audit_log is append-only");
    }

    @Test
    void appendingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> audit.append(entry(1))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void chainOfRealAuthorizationsVerifies() {
        fixtures.openAccount("5555555555554444", 100_000);
        for (int i = 0; i < 5; i++) {
            authorize.authorize(RequestBuilder.purchase("5555555555554444", 1_000).build());
        }

        assertThat(audit.verify()).isEqualTo(new ChainVerification.Intact(5));
    }

    @Test
    void tamperingWithARowIsDetectedAtExactlyThatSeq() {
        append(6);
        assertThat(audit.verify()).isInstanceOf(ChainVerification.Intact.class);
        long tampered = jdbc.sql("SELECT seq FROM audit_log ORDER BY seq OFFSET 3 LIMIT 1").query(Long.class).single();

        transactions.executeWithoutResult(status -> {
            jdbc.sql("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only").update();
            jdbc.sql("UPDATE audit_log SET details = jsonb_set(details, '{amountMinor}', '\"1\"') WHERE seq = :seq")
                    .param("seq", tampered).update();
            jdbc.sql("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only").update();
        });

        assertThat(audit.verify()).isInstanceOfSatisfying(ChainVerification.Broken.class, broken -> {
            assertThat(broken.firstBrokenSeq()).isEqualTo(tampered);
            assertThat(broken.recordsVerified()).isEqualTo(3);
        });
    }

    @Test
    void concurrentWritersProduceOneUnforkedChain() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                int n = i;
                executor.submit(() -> transactions.executeWithoutResult(status -> audit.append(entry(n))));
            }
        }

        assertThat(audit.verify()).isEqualTo(new ChainVerification.Intact(100));
        List<String> prevHashes = jdbc.sql("SELECT prev_hash FROM audit_log").query(String.class).list();
        assertThat(prevHashes).doesNotHaveDuplicates();
    }

    private void append(int count) {
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                audit.append(entry(i));
            }
        });
    }

    private static AuditEntry entry(int n) {
        return AuditEntry.of("test", "TEST_EVENT", "thing", Integer.toString(n),
                Map.of("amountMinor", Integer.toString(n * 100)));
    }
}
