package dev.cardrail.clearing.application;

import java.time.LocalDate;

public class SettlementRunInProgressException extends RuntimeException {

    public SettlementRunInProgressException(LocalDate businessDate) {
        super("Settlement for business date " + businessDate + " is running");
    }
}
