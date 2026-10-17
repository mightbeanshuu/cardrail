package dev.cardrail.iso8583.emv;

/** Malformed BER-TLV data. */
public final class TlvParseException extends Exception {

    private final int offset;

    public TlvParseException(int offset, String reason) {
        super("TLV at offset " + offset + ": " + reason);
        this.offset = offset;
    }

    /** Byte offset into the TLV data (not into the hex string) where the offending element starts. */
    public int offset() {
        return offset;
    }
}
