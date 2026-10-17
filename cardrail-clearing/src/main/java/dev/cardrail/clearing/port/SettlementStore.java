package dev.cardrail.clearing.port;

import dev.cardrail.clearing.domain.settlement.CurrencyTotals;
import dev.cardrail.clearing.domain.settlement.Position;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SettlementStore {

    /** Claims pending records for a run. Returns how many were claimed; fewer than asked means a race. */
    int claimForSettlement(LocalDate settlementDate, Collection<Long> recordIds);

    void addPartials(LocalDate settlementDate, List<Position> partials);

    /** Partial sums folded into one position per party and currency. */
    List<Position> sumPartials(LocalDate settlementDate);

    /** Count, gross and interchange of the claimed records, straight from the records. */
    List<CurrencyTotals> claimedRecordTotals(LocalDate settlementDate);

    void insertPositions(LocalDate settlementDate, List<Position> positions, Instant at);

    int markSettled(LocalDate settlementDate, Instant at);

    List<Position> positions(LocalDate settlementDate);
}
