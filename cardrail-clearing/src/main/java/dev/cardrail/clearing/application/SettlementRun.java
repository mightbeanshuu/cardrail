package dev.cardrail.clearing.application;

import dev.cardrail.clearing.domain.settlement.PartyType;
import dev.cardrail.clearing.domain.settlement.Position;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SettlementRun(LocalDate businessDate, RunStatus status, Instant startedAt, Instant finishedAt,
                            String failure, List<Position> positions) {

    /** Every record appears once on the acquirer side. */
    public long recordCount() {
        return positions.stream()
                .filter(p -> p.partyType() == PartyType.ACQUIRER)
                .mapToLong(Position::recordCount)
                .sum();
    }
}
