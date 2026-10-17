package dev.cardrail.clearing.domain.settlement;

import java.util.Comparator;

/** A party's settlement totals in one currency. Amounts are signed minor units. */
public record Position(PartyType partyType, String partyId, int currency, long recordCount, long grossMinor,
                       long interchangeMinor, long netMinor) {

    public static final Comparator<Position> REPORT_ORDER = Comparator
            .comparing(Position::partyType)
            .thenComparing(Position::partyId)
            .thenComparingInt(Position::currency);

    public static Position of(PartyType partyType, String partyId, int currency, long recordCount, long grossMinor,
                              long interchangeMinor) {
        return new Position(partyType, partyId, currency, recordCount, grossMinor, interchangeMinor,
                partyType.net(grossMinor, interchangeMinor));
    }

    Position plus(long count, long gross, long interchange) {
        return of(partyType, partyId, currency, recordCount + count, Math.addExact(grossMinor, gross),
                Math.addExact(interchangeMinor, interchange));
    }
}
