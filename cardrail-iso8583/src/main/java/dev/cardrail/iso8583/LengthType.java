package dev.cardrail.iso8583;

/** How a field's length is known: fixed by the spec, or carried in a 2/3-digit ASCII prefix. */
public enum LengthType {
    FIXED(0),
    LLVAR(2),
    LLLVAR(3);

    private final int prefixDigits;

    LengthType(int prefixDigits) {
        this.prefixDigits = prefixDigits;
    }

    public int prefixDigits() {
        return prefixDigits;
    }

    /** Largest length the prefix can express; unbounded (by the prefix) for fixed fields. */
    int prefixCapacity() {
        return switch (this) {
            case FIXED -> Integer.MAX_VALUE;
            case LLVAR -> 99;
            case LLLVAR -> 999;
        };
    }
}
