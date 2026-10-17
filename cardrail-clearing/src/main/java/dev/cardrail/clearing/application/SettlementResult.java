package dev.cardrail.clearing.application;

/** @param launched false when the business date had already been settled and nothing ran */
public record SettlementResult(SettlementRun run, boolean launched) {
}
