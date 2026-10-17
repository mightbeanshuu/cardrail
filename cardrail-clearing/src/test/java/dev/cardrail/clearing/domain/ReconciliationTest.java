package dev.cardrail.clearing.domain;

import dev.cardrail.clearing.domain.settlement.CurrencyTotals;
import dev.cardrail.clearing.domain.settlement.PartyType;
import dev.cardrail.clearing.domain.settlement.Position;
import dev.cardrail.clearing.domain.settlement.PositionTotals;
import dev.cardrail.clearing.domain.settlement.Reconciliation;
import dev.cardrail.clearing.domain.settlement.ReconciliationException;
import dev.cardrail.clearing.domain.settlement.SettlementItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationTest {

    private static final List<SettlementItem> ITEMS = List.of(
            new SettlementItem(1, "111111", "555555", 356, 20_000, 220),
            new SettlementItem(2, "111111", "411111", 356, 300_000, 2_000),
            new SettlementItem(3, "222222", "555555", 356, 45_050, 676),
            new SettlementItem(4, "222222", "411111", 840, 125_000, 3_375),
            new SettlementItem(5, "111111", "555555", 356, 10_000, 175),
            new SettlementItem(6, "111111", "555555", 356, -10_000, -175));

    private static final List<CurrencyTotals> RECORDS = List.of(
            new CurrencyTotals(356, 5, 365_050, 2_896),
            new CurrencyTotals(840, 1, 125_000, 3_375));

    @Test
    void positionsFollowTheInterchangeFlow() {
        List<Position> positions = totals();

        assertThat(positions).containsExactly(
                new Position(PartyType.ACQUIRER, "111111", 356, 4, 320_000, 2_220, 317_780),
                new Position(PartyType.ACQUIRER, "222222", 356, 1, 45_050, 676, 44_374),
                new Position(PartyType.ACQUIRER, "222222", 840, 1, 125_000, 3_375, 121_625),
                new Position(PartyType.ISSUER, "411111", 356, 1, 300_000, 2_000, -298_000),
                new Position(PartyType.ISSUER, "411111", 840, 1, 125_000, 3_375, -121_625),
                new Position(PartyType.ISSUER, "555555", 356, 4, 65_050, 896, -64_154),
                new Position(PartyType.NETWORK, "CARDRAIL", 356, 5, 365_050, 2_896, 0),
                new Position(PartyType.NETWORK, "CARDRAIL", 840, 1, 125_000, 3_375, 0));
        assertThatCode(() -> Reconciliation.verify(positions, RECORDS)).doesNotThrowAnyException();
    }

    @Test
    void anAcquirerOnlyAmountBreaksTheZeroSum() {
        List<Position> positions = new ArrayList<>(totals());
        Position acquirer = positions.getFirst();
        positions.set(0, Position.of(acquirer.partyType(), acquirer.partyId(), acquirer.currency(),
                acquirer.recordCount(), acquirer.grossMinor() + 1, acquirer.interchangeMinor()));

        assertThatThrownBy(() -> Reconciliation.verify(positions, RECORDS))
                .isInstanceOf(ReconciliationException.class)
                .hasMessageContaining("currency 356: acquirer + issuer + network net = 1, expected 0");
    }

    @Test
    void aNetThatDoesNotFollowFromGrossAndInterchangeIsCaught() {
        List<Position> positions = new ArrayList<>(totals());
        Position network = positions.get(6);
        positions.set(6, new Position(network.partyType(), network.partyId(), network.currency(),
                network.recordCount(), network.grossMinor(), network.interchangeMinor(), 5));

        assertThatThrownBy(() -> Reconciliation.verify(positions, RECORDS))
                .isInstanceOf(ReconciliationException.class)
                .hasMessageContaining("NETWORK CARDRAIL 356: net 5, expected 0")
                .hasMessageContaining("network net 5, expected 0");
    }

    @Test
    void positionsMustMatchTheRecordsTheyCameFrom() {
        List<CurrencyTotals> oneRecordMissing = List.of(
                new CurrencyTotals(356, 4, 345_050, 2_676),
                new CurrencyTotals(840, 1, 125_000, 3_375));

        assertThatThrownBy(() -> Reconciliation.verify(totals(), oneRecordMissing))
                .isInstanceOf(ReconciliationException.class)
                .hasMessageContaining("currency 356: clearing records (count 4");
    }

    @Test
    void aCurrencyWithRecordsButNoPositionsIsCaught() {
        List<CurrencyTotals> extraCurrency = new ArrayList<>(RECORDS);
        extraCurrency.add(new CurrencyTotals(978, 1, 5_000, 50));

        assertThatThrownBy(() -> Reconciliation.verify(totals(), extraCurrency))
                .hasMessageContaining("currency 978");
    }

    @Test
    void nothingToSettleReconciles() {
        assertThatCode(() -> Reconciliation.verify(List.of(), List.of())).doesNotThrowAnyException();
    }

    private static List<Position> totals() {
        PositionTotals totals = new PositionTotals("CARDRAIL");
        ITEMS.forEach(totals::add);
        return totals.positions();
    }
}
