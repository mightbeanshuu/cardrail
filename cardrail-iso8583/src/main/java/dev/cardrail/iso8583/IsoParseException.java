package dev.cardrail.iso8583;

/**
 * A received message could not be decoded. Callers on the network path typically answer with
 * response code {@code 30} (format error) when enough of the message was read to build a response,
 * or drop the frame otherwise.
 */
public final class IsoParseException extends Exception {

    /** Field number used for failures in the MTI, bitmaps or framing rather than in a data element. */
    public static final int NO_FIELD = -1;

    private final int field;
    private final int offset;
    private final String reason;

    public IsoParseException(int field, int offset, String reason) {
        super((field == NO_FIELD ? "header" : "field " + field) + " at offset " + offset + ": " + reason);
        this.field = field;
        this.offset = offset;
        this.reason = reason;
    }

    /** Field being decoded when the failure occurred, or {@link #NO_FIELD}. */
    public int field() {
        return field;
    }

    /** Byte offset into the message (not the frame) where the offending data starts. */
    public int offset() {
        return offset;
    }

    public String reason() {
        return reason;
    }
}
