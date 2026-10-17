package dev.cardrail.clearing.domain.settlement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Accumulates items into acquirer, issuer and network positions. Not thread safe. */
public final class PositionTotals {

    private record Key(PartyType partyType, String partyId, int currency) {
    }

    private final String networkId;
    private final Map<Key, Position> positions = new LinkedHashMap<>();

    public PositionTotals(String networkId) {
        this.networkId = networkId;
    }

    public PositionTotals add(SettlementItem item) {
        add(PartyType.ACQUIRER, item.acquirerId(), item);
        add(PartyType.ISSUER, item.issuerBin(), item);
        add(PartyType.NETWORK, networkId, item);
        return this;
    }

    public List<Position> positions() {
        List<Position> result = new ArrayList<>(positions.values());
        result.sort(Position.REPORT_ORDER);
        return result;
    }

    private void add(PartyType type, String partyId, SettlementItem item) {
        positions.merge(new Key(type, partyId, item.currency()),
                Position.of(type, partyId, item.currency(), 1, item.amountMinor(), item.interchangeFeeMinor()),
                (current, added) -> current.plus(1, item.amountMinor(), item.interchangeFeeMinor()));
    }
}
