package dev.cardrail.clearing.domain.settlement;

/**
 * Who a settlement position belongs to. Net is what the party receives from (+) or pays to (-) the
 * network's settlement account. Interchange flows from acquirer to issuer through the network:
 * the acquirer is paid gross less interchange, the issuer pays gross less interchange, and the
 * network passes interchange through and keeps nothing. Scheme fees, the network's own revenue,
 * are out of scope.
 */
public enum PartyType {
    ACQUIRER,
    ISSUER,
    NETWORK;

    public long net(long grossMinor, long interchangeMinor) {
        return switch (this) {
            case ACQUIRER -> Math.subtractExact(grossMinor, interchangeMinor);
            case ISSUER -> Math.subtractExact(interchangeMinor, grossMinor);
            case NETWORK -> 0;
        };
    }
}
