package dev.cardrail.clearing.integration;

import dev.cardrail.clearing.application.RunStatus;
import dev.cardrail.clearing.application.SettlementResult;
import dev.cardrail.clearing.application.SettlementRun;
import dev.cardrail.clearing.application.SettlementRunFailedException;
import dev.cardrail.clearing.application.SettlementService;
import dev.cardrail.clearing.application.SettlementProperties;
import dev.cardrail.clearing.domain.settlement.Position;
import dev.cardrail.clearing.port.SettlementStore;
import dev.cardrail.clearing.support.ClearingIntegrationTest;
import dev.cardrail.clearing.support.SettlementStoreFaults;
import dev.cardrail.clearing.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ClearingIntegrationTest
class SettlementJobIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2025, 3, 14);

    @Autowired TestFixtures fixtures;
    @Autowired SettlementService settlement;
    @Autowired SettlementStore store;
    @Autowired SettlementProperties properties;
    @Autowired SettlementStoreFaults faults;
    @Autowired JobLauncherTestUtils jobs;
    @Autowired JobExplorer explorer;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void settlesTheDayIntoReconciledPositionsAndACsvReport() throws Exception {
        SettlementDataset.load(fixtures, DAY);

        SettlementResult result = settlement.settle(DAY);

        assertThat(result.launched()).isTrue();
        SettlementRun run = result.run();
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.positions()).containsExactlyElementsOf(SettlementDataset.expectedPositions());
        assertThat(run.recordCount()).isEqualTo(6);
        for (int currency : new int[] {356, 840}) {
            assertThat(run.positions().stream().filter(p -> p.currency() == currency).mapToLong(Position::netMinor).sum())
                    .as("acquirer + issuer + network nets in %d", currency).isZero();
        }
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE status = 'SETTLED' AND settlement_date = ?", DAY))
                .isEqualTo(6);
        assertThat(Files.readString(properties.reportDirectory().resolve("settlement-2025-03-14.csv"), StandardCharsets.UTF_8))
                .isEqualTo(SettlementDataset.expectedCsv(DAY));
    }

    @Test
    void aCompletedDateIsNotSettledAgain() throws Exception {
        SettlementDataset.load(fixtures, DAY);
        SettlementRun first = settlement.settle(DAY).run();
        fixtures.capture("111111", SettlementDataset.BIN_A, "5411", 356, 99_999, TestFixtures.morningOf(DAY));

        SettlementResult again = settlement.settle(DAY);

        assertThat(again.launched()).isFalse();
        assertThat(again.run().positions()).isEqualTo(first.positions());
        assertThat(explorer.getJobInstanceCount("settlementJob")).isOne();
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE status = 'PENDING_SETTLEMENT'"))
                .as("the late capture waits for the next run").isOne();
    }

    @Test
    void aRecordLeftPendingOnAnEarlierDateIsSettledByTheNextRun() {
        fixtures.capture("111111", SettlementDataset.BIN_A, "5411", 356, 20_000, TestFixtures.morningOf(DAY.minusDays(1)));
        SettlementDataset.load(fixtures, DAY);

        SettlementRun run = settlement.settle(DAY).run();

        assertThat(run.recordCount()).isEqualTo(7);
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE business_date = ? AND settlement_date = ?",
                DAY.minusDays(1), DAY)).isOne();
    }

    @Test
    void aFailedRunResumesFromItsLastCommittedChunk() {
        SettlementDataset.load(fixtures, DAY);
        faults.failOnChunk(2);

        assertThatThrownBy(() -> settlement.settle(DAY))
                .isInstanceOf(SettlementRunFailedException.class)
                .hasMessageContaining("injected fault in chunk 2");
        assertThat(settlement.find(DAY).status()).isEqualTo(RunStatus.FAILED);
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE settlement_date = ?", DAY))
                .as("chunk 1 committed, chunk 2 rolled back").isEqualTo(3);
        assertThat(store.positions(DAY)).isEmpty();

        faults.clear();
        SettlementResult resumed = settlement.settle(DAY);

        assertThat(resumed.launched()).isTrue();
        assertThat(resumed.run().positions()).containsExactlyElementsOf(SettlementDataset.expectedPositions());
        List<JobExecution> executions = explorer.getJobExecutions(explorer.getJobInstances("settlementJob", 0, 1).getFirst());
        assertThat(executions).hasSize(2);
        JobExecution restart = executions.stream().filter(e -> e.getStatus() == BatchStatus.COMPLETED).findFirst().orElseThrow();
        StepExecution accumulate = restart.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals("accumulatePositions")).findFirst().orElseThrow();
        assertThat(accumulate.getReadCount()).as("only the records after the last commit are read again").isEqualTo(3);
    }

    @Test
    void positionsThatDoNotReconcileFailTheStepAndSettleNothing() {
        SettlementDataset.load(fixtures, DAY);
        assertThat(jobs.launchStep("accumulatePositions", parameters()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        fixtures.jdbc().sql("UPDATE settlement_partial SET gross_minor = gross_minor + 1, net_minor = net_minor + 1 "
                + "WHERE party_type = 'ACQUIRER'").update();

        JobExecution publish = jobs.launchStep("publishPositions", parameters());

        assertThat(publish.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publish.getAllFailureExceptions()).singleElement().satisfies(e -> assertThat(e.getMessage())
                .contains("settlement does not reconcile")
                .contains("acquirer + issuer + network net"));
        assertThat(store.positions(DAY)).isEmpty();
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE status = 'SETTLED'")).isZero();
    }

    @Test
    void positionsThatDisagreeWithTheirRecordsFailTheStep() {
        SettlementDataset.load(fixtures, DAY);
        assertThat(jobs.launchStep("accumulatePositions", parameters()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> one = fixtures.jdbc().sql("SELECT id FROM clearing_record WHERE currency = 840").query().singleRow();
        fixtures.jdbc().sql("UPDATE clearing_record SET amount_minor = amount_minor + 100 WHERE id = ?")
                .param(((Number) one.values().iterator().next()).longValue()).update();

        JobExecution publish = jobs.launchStep("publishPositions", parameters());

        assertThat(publish.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publish.getAllFailureExceptions().getFirst().getMessage())
                .contains("currency 840: clearing records (count 1, gross 125100");
        assertThat(fixtures.count("SELECT COUNT(*) FROM clearing_record WHERE status = 'SETTLED'")).isZero();
    }

    /** launchStep runs each step as its own throwaway job, which needs its own identifying parameters. */
    private static JobParameters parameters() {
        return new JobParametersBuilder()
                .addLocalDate("businessDate", DAY)
                .addString("test.run", UUID.randomUUID().toString())
                .toJobParameters();
    }
}
