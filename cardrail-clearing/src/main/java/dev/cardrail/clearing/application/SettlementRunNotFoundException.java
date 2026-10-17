package dev.cardrail.clearing.application;

import java.time.LocalDate;

public class SettlementRunNotFoundException extends RuntimeException {

    public SettlementRunNotFoundException(LocalDate businessDate) {
        super("No settlement run for business date " + businessDate);
    }
}
