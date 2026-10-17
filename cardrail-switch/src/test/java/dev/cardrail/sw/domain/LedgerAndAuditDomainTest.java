package dev.cardrail.sw.domain;

import dev.cardrail.sw.domain.audit.AuditChainVerifier;
import dev.cardrail.sw.domain.audit.AuditEntry;
import dev.cardrail.sw.domain.audit.AuditHasher;
import dev.cardrail.sw.domain.audit.AuditRecord;
import dev.cardrail.sw.domain.audit.ChainVerification;
import dev.cardrail.sw.domain.ledger.Journal;
import dev.cardrail.sw.domain.ledger.JournalKind;
import dev.cardrail.sw.domain.ledger.Posting;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerAndAuditDomainTest {

    @Test
    void journalMustBalance() {
        assertThatThrownBy(() -> new Journal(UUID.randomUUID(), JournalKind.HOLD, null,
                List.of(new Posting(1, -100), new Posting(2, 99))))
                .hasMessageContaining("sum to -1");
    }

    @Test
    void captureJournalSplitsTheHoldBetweenSettlementAndAvailable() {
        Journal capture = Journal.builder(JournalKind.CAPTURE, UUID.randomUUID())
                .move(10, 20, 700)
                .move(10, 30, 300)
                .build();
        assertThat(capture.postings()).containsExactly(
                new Posting(10, -700), new Posting(20, 700), new Posting(10, -300), new Posting(30, 300));
    }

    @Test
    void zeroAmountMovesAreSkipped() {
        Journal full = Journal.builder(JournalKind.CAPTURE, null).move(10, 20, 1000).move(10, 30, 0).build();
        assertThat(full.postings()).hasSize(2);
    }

    @Test
    void hashChangesWithAnyFieldIncludingThePreviousHash() {
        Instant at = Instant.parse("2026-10-17T10:00:00.123456Z");
        TreeMap<String, String> details = new TreeMap<>(Map.of("k", "v"));
        String base = AuditHasher.hash(1, at, "a", "ACT", "e", "1", details, AuditHasher.GENESIS_HASH);

        assertThat(AuditHasher.hash(1, at, "a", "ACT", "e", "1", details, AuditHasher.GENESIS_HASH)).isEqualTo(base);
        assertThat(AuditHasher.hash(2, at, "a", "ACT", "e", "1", details, AuditHasher.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(AuditHasher.hash(1, at, "a", "ACT", "e", "1", new TreeMap<>(Map.of("k", "w")),
                AuditHasher.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(AuditHasher.hash(1, at, "a", "ACT", "e", "1", details, "f".repeat(64))).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotBeShiftedToForgeTheSameHash() {
        Instant at = Instant.parse("2026-10-17T10:00:00Z");
        TreeMap<String, String> none = new TreeMap<>();
        assertThat(AuditHasher.hash(1, at, "ab", "c", "e", "1", none, AuditHasher.GENESIS_HASH))
                .isNotEqualTo(AuditHasher.hash(1, at, "a", "bc", "e", "1", none, AuditHasher.GENESIS_HASH));
    }

    @Test
    void verifierAcceptsAnIntactChain() {
        AuditChainVerifier verifier = new AuditChainVerifier();
        for (AuditRecord record : chain(5)) {
            assertThat(verifier.accept(record)).isEmpty();
        }
        assertThat(verifier.intact().recordsVerified()).isEqualTo(5);
    }

    @Test
    void verifierReportsTheFirstRecordWhoseContentChanged() {
        List<AuditRecord> records = chain(5);
        AuditRecord original = records.get(2);
        records.set(2, new AuditRecord(original.seq(), original.occurredAt(), "intruder", original.action(),
                original.entity(), original.entityId(), original.details(), original.prevHash(), original.hash()));

        assertThat(firstBreak(records)).hasValueSatisfying(broken -> {
            assertThat(broken.firstBrokenSeq()).isEqualTo(3);
            assertThat(broken.reason()).contains("content");
        });
    }

    @Test
    void verifierReportsADeletedRecordAtTheRecordAfterTheGap() {
        List<AuditRecord> records = chain(5);
        records.remove(1);

        assertThat(firstBreak(records)).hasValueSatisfying(broken -> {
            assertThat(broken.firstBrokenSeq()).isEqualTo(3);
            assertThat(broken.reason()).contains("prev_hash");
        });
    }

    private static Optional<ChainVerification.Broken> firstBreak(List<AuditRecord> records) {
        AuditChainVerifier verifier = new AuditChainVerifier();
        for (AuditRecord record : records) {
            Optional<ChainVerification.Broken> broken = verifier.accept(record);
            if (broken.isPresent()) {
                return broken;
            }
        }
        return Optional.empty();
    }

    private static List<AuditRecord> chain(int length) {
        List<AuditRecord> records = new ArrayList<>();
        String prev = AuditHasher.GENESIS_HASH;
        for (int seq = 1; seq <= length; seq++) {
            AuditRecord record = AuditRecord.chain(seq, Instant.parse("2026-10-17T10:00:00Z").plusSeconds(seq),
                    AuditEntry.of("tester", "TEST", "thing", Integer.toString(seq), Map.of("n", Integer.toString(seq))),
                    prev);
            records.add(record);
            prev = record.hash();
        }
        return records;
    }
}
