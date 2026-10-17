package dev.cardrail.clearing.application;

import java.time.Instant;

/** The latest execution of the settlement job for one business date. */
public record JobRun(RunStatus status, Instant startedAt, Instant finishedAt, String failure) {
}
