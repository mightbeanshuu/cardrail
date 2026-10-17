package dev.cardrail.clearing.domain.settlement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Double-entry check on a settlement run, per currency:
 * <ol>
 *   <li>every position's net follows from its gross and interchange;</li>
 *   <li>acquirer net + issuer net + network net = 0, and the network keeps nothing;</li>
 *   <li>acquirer and issuer sides saw the same records, volume and interchange;</li>
 *   <li>those totals equal the clearing records stamped for the run, counted separately in SQL.</li>
 * </ol>
 * The first three catch a broken aggregation; the last catches a record counted twice or missed.
 */
public final class Reconciliation {

    private Reconciliation() {
    }

    public static void verify(Collection<Position> positions, Collection<CurrencyTotals> recordTotals) {
        List<String> violations = new ArrayList<>();
        for (Position p : positions) {
            long expected = p.partyType().net(p.grossMinor(), p.interchangeMinor());
            if (p.netMinor() != expected) {
                violations.add("%s %s %d: net %d, expected %d".formatted(
                        p.partyType(), p.partyId(), p.currency(), p.netMinor(), expected));
            }
        }

        Map<Integer, CurrencyTotals> fromRecords = recordTotals.stream()
                .collect(Collectors.toMap(CurrencyTotals::currency, Function.identity()));
        Map<Integer, List<Position>> byCurrency = positions.stream()
                .collect(Collectors.groupingBy(Position::currency, TreeMap::new, Collectors.toList()));
        TreeSet<Integer> currencies = new TreeSet<>(byCurrency.keySet());
        currencies.addAll(fromRecords.keySet());

        for (int currency : currencies) {
            List<Position> ps = byCurrency.getOrDefault(currency, List.of());
            Side acquirer = Side.of(ps, PartyType.ACQUIRER);
            Side issuer = Side.of(ps, PartyType.ISSUER);
            Side network = Side.of(ps, PartyType.NETWORK);

            long netSum = acquirer.net + issuer.net + network.net;
            if (netSum != 0) {
                violations.add("currency %d: acquirer + issuer + network net = %d, expected 0".formatted(currency, netSum));
            }
            if (network.net != 0) {
                violations.add("currency %d: network net %d, expected 0".formatted(currency, network.net));
            }
            compare(violations, currency, "issuer", issuer, acquirer);
            compare(violations, currency, "network", network, acquirer);

            CurrencyTotals records = fromRecords.getOrDefault(currency, new CurrencyTotals(currency, 0, 0, 0));
            compare(violations, currency, "clearing records",
                    new Side(records.recordCount(), records.grossMinor(), records.interchangeMinor(), 0), acquirer);
        }
        if (!violations.isEmpty()) {
            throw new ReconciliationException(violations);
        }
    }

    private static void compare(List<String> violations, int currency, String name, Side side, Side acquirer) {
        if (side.count != acquirer.count || side.gross != acquirer.gross || side.interchange != acquirer.interchange) {
            violations.add("currency %d: %s (count %d, gross %d, interchange %d) differs from acquirers (count %d, gross %d, interchange %d)"
                    .formatted(currency, name, side.count, side.gross, side.interchange,
                            acquirer.count, acquirer.gross, acquirer.interchange));
        }
    }

    private record Side(long count, long gross, long interchange, long net) {

        static Side of(List<Position> positions, PartyType type) {
            long count = 0;
            long gross = 0;
            long interchange = 0;
            long net = 0;
            for (Position p : positions) {
                if (p.partyType() == type) {
                    count += p.recordCount();
                    gross = Math.addExact(gross, p.grossMinor());
                    interchange = Math.addExact(interchange, p.interchangeMinor());
                    net = Math.addExact(net, p.netMinor());
                }
            }
            return new Side(count, gross, interchange, net);
        }
    }
}
