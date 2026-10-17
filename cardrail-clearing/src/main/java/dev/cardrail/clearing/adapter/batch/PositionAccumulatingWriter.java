package dev.cardrail.clearing.adapter.batch;

import dev.cardrail.clearing.domain.settlement.PositionTotals;
import dev.cardrail.clearing.domain.settlement.SettlementItem;
import dev.cardrail.clearing.port.SettlementStore;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.time.LocalDate;
import java.util.List;

/**
 * Claims a chunk's records for the run and stores the chunk's partial sums. Both happen in the
 * chunk transaction together with the reader's restart point, so after a crash a chunk is either
 * fully counted or not at all.
 */
class PositionAccumulatingWriter implements ItemWriter<SettlementItem> {

    private final SettlementStore store;
    private final String networkId;
    private final LocalDate settlementDate;

    PositionAccumulatingWriter(SettlementStore store, String networkId, LocalDate settlementDate) {
        this.store = store;
        this.networkId = networkId;
        this.settlementDate = settlementDate;
    }

    @Override
    public void write(Chunk<? extends SettlementItem> chunk) {
        List<Long> ids = chunk.getItems().stream().map(SettlementItem::id).toList();
        int claimed = store.claimForSettlement(settlementDate, ids);
        if (claimed != ids.size()) {
            throw new IllegalStateException("claimed " + claimed + " of " + ids.size()
                    + " records for settlement date " + settlementDate + "; another run settled some of them");
        }
        PositionTotals totals = new PositionTotals(networkId);
        chunk.forEach(totals::add);
        store.addPartials(settlementDate, totals.positions());
    }
}
