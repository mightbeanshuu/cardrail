package dev.cardrail.clearing.adapter.batch;

import dev.cardrail.clearing.domain.settlement.PartyType;
import dev.cardrail.clearing.domain.settlement.Position;
import dev.cardrail.clearing.domain.settlement.Reconciliation;
import dev.cardrail.clearing.domain.settlement.ReconciliationException;
import dev.cardrail.clearing.port.SettlementStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Runs in one transaction: if reconciliation fails nothing is published and no record is marked settled. */
class PublishPositionsTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(PublishPositionsTasklet.class);

    private final SettlementStore store;
    private final Clock clock;
    private final LocalDate settlementDate;

    PublishPositionsTasklet(SettlementStore store, Clock clock, LocalDate settlementDate) {
        this.store = store;
        this.clock = clock;
        this.settlementDate = settlementDate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        List<Position> positions = store.sumPartials(settlementDate);
        Reconciliation.verify(positions, store.claimedRecordTotals(settlementDate));

        Instant now = clock.instant();
        store.insertPositions(settlementDate, positions, now);
        long expected = positions.stream()
                .filter(p -> p.partyType() == PartyType.ACQUIRER)
                .mapToLong(Position::recordCount)
                .sum();
        int settled = store.markSettled(settlementDate, now);
        if (settled != expected) {
            throw new ReconciliationException(List.of(
                    "positions cover " + expected + " records but " + settled + " were marked settled"));
        }
        contribution.incrementWriteCount(settled);
        log.info("settlement {}: {} positions, {} records settled", settlementDate, positions.size(), settled);
        return RepeatStatus.FINISHED;
    }
}
