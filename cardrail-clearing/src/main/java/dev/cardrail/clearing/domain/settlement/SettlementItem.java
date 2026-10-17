package dev.cardrail.clearing.domain.settlement;

/** The part of a clearing record that settlement needs. */
public record SettlementItem(long id, String acquirerId, String issuerBin, int currency, long amountMinor,
                             long interchangeFeeMinor) {
}
