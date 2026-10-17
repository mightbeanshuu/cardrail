package dev.cardrail.clearing.application;

public class SettlementRunFailedException extends RuntimeException {

    private final SettlementRun run;

    public SettlementRunFailedException(SettlementRun run) {
        super("Settlement for business date " + run.businessDate() + " failed: " + run.failure());
        this.run = run;
    }

    public SettlementRun run() {
        return run;
    }
}
