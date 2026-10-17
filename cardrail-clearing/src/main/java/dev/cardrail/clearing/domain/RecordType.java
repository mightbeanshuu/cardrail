package dev.cardrail.clearing.domain;

public enum RecordType {
    CAPTURE,
    /** Offsets a capture in the same business date; history is never updated in place. */
    REVERSAL
}
