package dev.cardrail.clearing.application;

public enum RunStatus {
    RUNNING,
    COMPLETED,
    /** Stopped before completing; launching the same business date again resumes it. */
    FAILED
}
