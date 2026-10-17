package dev.cardrail.clearing.adapter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cardrail.clearing.application.SettlementRun;
import dev.cardrail.clearing.domain.settlement.Position;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record SettlementRunView(LocalDate businessDate, String status, Instant startedAt, Instant finishedAt,
                         String failure, long recordCount, List<PositionView> positions, String reportUrl) {

    record PositionView(String partyType, String partyId, int currency, long recordCount, long grossMinor,
                        long interchangeMinor, long netMinor) {

        static PositionView of(Position p) {
            return new PositionView(p.partyType().name(), p.partyId(), p.currency(), p.recordCount(),
                    p.grossMinor(), p.interchangeMinor(), p.netMinor());
        }
    }

    static SettlementRunView of(SettlementRun run) {
        return new SettlementRunView(run.businessDate(), run.status().name(), run.startedAt(), run.finishedAt(),
                run.failure(), run.recordCount(), run.positions().stream().map(PositionView::of).toList(),
                SettlementRunController.reportPath(run.businessDate()));
    }
}
