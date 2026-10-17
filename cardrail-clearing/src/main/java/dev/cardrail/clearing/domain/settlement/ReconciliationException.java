package dev.cardrail.clearing.domain.settlement;

import java.util.List;

public class ReconciliationException extends RuntimeException {

    private final List<String> violations;

    public ReconciliationException(List<String> violations) {
        super("settlement does not reconcile: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
