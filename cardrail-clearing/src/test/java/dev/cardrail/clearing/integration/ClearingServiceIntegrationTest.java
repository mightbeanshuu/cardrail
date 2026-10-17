package dev.cardrail.clearing.integration;

import dev.cardrail.clearing.application.CapturedAuthorization;
import dev.cardrail.clearing.application.ClearingService;
import dev.cardrail.clearing.application.EventOutcome;
import dev.cardrail.clearing.application.ReversedAuthorization;
import dev.cardrail.clearing.domain.ClearingRecord;
import dev.cardrail.clearing.domain.RecordType;
import dev.cardrail.clearing.port.ClearingRecords;
import dev.cardrail.clearing.support.ClearingIntegrationTest;
import dev.cardrail.clearing.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@ClearingIntegrationTest
class ClearingServiceIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2025, 3, 14);

    @Autowired TestFixtures fixtures;
    @Autowired ClearingService clearing;
    @Autowired ClearingRecords records;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void captureIsRecordedOnceWithInterchangeFromTheRateTable() {
        CapturedAuthorization capture = capture(UUID.randomUUID(), "5541", 300_000, TestFixtures.morningOf(DAY));

        assertThat(clearing.onCaptured(capture, "test")).isEqualTo(EventOutcome.RECORDED);
        assertThat(clearing.onCaptured(capture, "test")).isEqualTo(EventOutcome.DUPLICATE);

        ClearingRecord record = records.findCapture(capture.authorizationId()).orElseThrow();
        assertThat(record.interchangeCategory()).isEqualTo("FUEL");
        assertThat(record.interchangeFeeMinor()).as("illustrative 50 bps + 500").isEqualTo(2_000);
        assertThat(record.issuerBin()).isEqualTo("555555");
        assertThat(record.businessDate()).isEqualTo(DAY);
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record")).isOne();
    }

    @Test
    void anMccWithoutACategoryUsesTheDefaultRate() {
        UUID id = UUID.randomUUID();
        clearing.onCaptured(capture(id, "7999", 10_000, TestFixtures.morningOf(DAY)), "test");

        ClearingRecord record = records.findCapture(id).orElseThrow();
        assertThat(record.interchangeCategory()).isEqualTo("DEFAULT");
        assertThat(record.interchangeFeeMinor()).isEqualTo(175);
    }

    @Test
    void concurrentDeliveriesOfOneEventCommitOnce() throws Exception {
        CapturedAuthorization capture = capture(UUID.randomUUID(), "5411", 20_000, TestFixtures.morningOf(DAY));
        Callable<EventOutcome> deliver = () -> clearing.onCaptured(capture, "test");

        List<EventOutcome> outcomes;
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<EventOutcome>> futures = pool.invokeAll(List.of(deliver, deliver, deliver, deliver));
            outcomes = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        assertThat(outcomes).containsOnlyOnce(EventOutcome.RECORDED);
        assertThat(outcomes).filteredOn(o -> o == EventOutcome.DUPLICATE).hasSize(3);
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record")).isOne();
    }

    @Test
    void capturesEitherSideOfTheCutOffLandOnConsecutiveBusinessDates() {
        UUID before = UUID.randomUUID();
        UUID at = UUID.randomUUID();
        // 23:00 IST on 14 March 2025 is 17:30 UTC.
        clearing.onCaptured(capture(before, "5411", 1_000, Instant.parse("2025-03-14T17:29:59.999Z")), "test");
        clearing.onCaptured(capture(at, "5411", 1_000, Instant.parse("2025-03-14T17:30:00Z")), "test");

        assertThat(records.findCapture(before).orElseThrow().businessDate()).isEqualTo(DAY);
        assertThat(records.findCapture(at).orElseThrow().businessDate()).isEqualTo(DAY.plusDays(1));
    }

    @Test
    void aReversalInTheSameBusinessDateAddsAnOffsettingRecord() {
        UUID id = UUID.randomUUID();
        clearing.onCaptured(capture(id, "5812", 45_050, TestFixtures.morningOf(DAY)), "test");
        ReversedAuthorization reversal = new ReversedAuthorization(id, TestFixtures.morningOf(DAY).plusSeconds(600));

        assertThat(clearing.onReversed(reversal, "test")).isEqualTo(EventOutcome.RECORDED);
        assertThat(clearing.onReversed(reversal, "test")).isEqualTo(EventOutcome.DUPLICATE);

        List<ClearingRecord> both = records.findByBusinessDate(DAY, 0, 10);
        assertThat(both).extracting(ClearingRecord::type).containsExactly(RecordType.CAPTURE, RecordType.REVERSAL);
        assertThat(both).extracting(ClearingRecord::amountMinor).containsExactly(45_050L, -45_050L);
        assertThat(both).extracting(ClearingRecord::interchangeFeeMinor).containsExactly(676L, -676L);
        assertThat(both.get(0).occurredAt()).as("the capture is untouched").isEqualTo(TestFixtures.morningOf(DAY));
    }

    @Test
    void aReversalWithoutACaptureIsRememberedAsIgnored() {
        UUID id = UUID.randomUUID();

        assertThat(clearing.onReversed(new ReversedAuthorization(id, TestFixtures.morningOf(DAY)), "test"))
                .isEqualTo(EventOutcome.IGNORED);
        assertThat(clearing.onReversed(new ReversedAuthorization(id, TestFixtures.morningOf(DAY)), "test"))
                .isEqualTo(EventOutcome.DUPLICATE);

        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record")).isZero();
        Map<String, Object> marker = fixtures.jdbc()
                .sql("SELECT outcome, detail FROM processed_event WHERE authorization_id = ?")
                .param(id.toString()).query().singleRow();
        assertThat(marker.values()).containsExactly("IGNORED", "no capture to offset");
    }

    @Test
    void aReversalInALaterBusinessDateDoesNotRewriteAClearedDay() {
        UUID id = UUID.randomUUID();
        clearing.onCaptured(capture(id, "5411", 20_000, TestFixtures.morningOf(DAY)), "test");

        assertThat(clearing.onReversed(new ReversedAuthorization(id, TestFixtures.morningOf(DAY.plusDays(1))), "test"))
                .isEqualTo(EventOutcome.IGNORED);
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record")).isOne();
    }

    private static CapturedAuthorization capture(UUID id, String mcc, long amount, Instant at) {
        return new CapturedAuthorization(id, "tok-" + id, "555555******4444", "123456", "MERCHANT000001", mcc, 356,
                amount, at);
    }
}
