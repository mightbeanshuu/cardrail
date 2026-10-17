package dev.cardrail.iso8583.emv;

/** Tag-number encoding rules (ISO/IEC 8825-1 / X.690 section 8.1.2) for tags packed into an int. */
final class Tags {

    private Tags() {
    }

    static int byteCount(int tag) {
        return Math.max(1, (32 - Integer.numberOfLeadingZeros(tag) + 7) / 8);
    }

    static int firstByte(int tag) {
        return tag >>> (8 * (byteCount(tag) - 1)) & 0xFF;
    }

    static byte[] bytes(int tag) {
        int n = byteCount(tag);
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (tag >>> (8 * (n - 1 - i)));
        }
        return b;
    }

    /**
     * A single-byte tag must not have its low 5 bits all set (that means "more bytes follow"); a
     * multi-byte tag must, and each subsequent byte carries bit 8 set except the last.
     */
    static void validate(int tag) {
        byte[] b = bytes(tag);
        boolean multi = (b[0] & 0x1F) == 0x1F;
        if (multi != (b.length > 1)) {
            throw new IllegalArgumentException("malformed tag " + Tlv.tagHex(tag)
                    + (multi ? ": low 5 bits 11111 require subsequent bytes" : ": subsequent bytes not announced"));
        }
        for (int i = 1; i < b.length; i++) {
            boolean more = (b[i] & 0x80) != 0;
            if (more != (i < b.length - 1)) {
                throw new IllegalArgumentException("malformed tag " + Tlv.tagHex(tag) + ": continuation bit in byte " + i);
            }
        }
    }
}
