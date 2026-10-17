package dev.cardrail.iso8583;

/**
 * Character class of a field's value as it appears on the wire.
 *
 * <p>{@link #B} is binary data carried as hex ASCII, so its length is counted in hex characters.
 */
public enum FieldType {
    /** Digits {@code 0-9}. */
    N,
    /** Letters, digits and space. Space is admitted because fixed AN fields are right-space-padded. */
    AN,
    /** Printable ASCII, {@code 0x20-0x7E}. */
    ANS,
    /** Binary carried as hex ASCII: {@code 0-9}, {@code A-F} (lowercase accepted, normalised to upper). */
    B;

    public boolean accepts(char c) {
        return switch (this) {
            case N -> c >= '0' && c <= '9';
            case AN -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ';
            case ANS -> c >= 0x20 && c <= 0x7E;
            case B -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
        };
    }

    /** Index of the first character this type rejects, or -1 if all are accepted. */
    int firstInvalid(CharSequence value) {
        for (int i = 0; i < value.length(); i++) {
            if (!accepts(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
