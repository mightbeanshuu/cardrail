package dev.cardrail.clearing.application;

import java.time.LocalDate;

public class SettlementReportNotReadyException extends RuntimeException {

    public SettlementReportNotReadyException(LocalDate businessDate, RunStatus status) {
        super("Settlement for business date " + businessDate + " is " + status + "; the report exists once it completes");
    }
}
