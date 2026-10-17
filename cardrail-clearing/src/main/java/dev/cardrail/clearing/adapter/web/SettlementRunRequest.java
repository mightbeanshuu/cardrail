package dev.cardrail.clearing.adapter.web;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

record SettlementRunRequest(@NotNull LocalDate businessDate) {
}
