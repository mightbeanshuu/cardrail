package dev.cardrail.clearing.application;

import java.time.LocalDate;

public class BusinessDateNotStartedException extends RuntimeException {

    public BusinessDateNotStartedException(LocalDate requested, LocalDate current) {
        super("Business date " + requested + " has not started; the current business date is " + current);
    }
}
