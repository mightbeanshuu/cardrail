package dev.cardrail.clearing.domain.settlement;

/** Totals taken straight from the clearing records of a run, independently of the positions. */
public record CurrencyTotals(int currency, long recordCount, long grossMinor, long interchangeMinor) {
}
