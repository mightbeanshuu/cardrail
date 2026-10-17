package dev.cardrail.iso8583;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Encodes and decodes the ASCII ISO 8583 variant described in {@code docs/SPEC.md}:
 * MTI (4 ASCII digits), primary bitmap (16 hex chars), optional secondary bitmap (16 hex chars,
 * present iff bit 1 is set), then data elements in ascending field order. Variable fields carry a
 * 2- or 3-digit ASCII length prefix counting characters (hex characters for binary fields).
 *
 * <p>Decoding is strict and canonical: anything the encoder would not have produced is rejected,
 * with the exception of lowercase hex, which is accepted and normalised to uppercase.
 * Instances are immutable and thread-safe.
 */
public final class IsoCodec {

    private static final int MTI_LENGTH = 4;
    private static final int BITMAP_LENGTH = 16;

    /** Fields a response copies from its request when present. 38 and 39 are set by the responder. */
    private static final Set<Integer> ECHOED_IN_RESPONSE = Set.of(2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49, 70, 90);

    private final MessageSpec spec;

    public IsoCodec() {
        this(MessageSpec.defaultSpec());
    }

    public IsoCodec(MessageSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    public MessageSpec spec() {
        return spec;
    }

    /**
     * @throws IllegalArgumentException if the message carries a field this codec's spec does not
     *                                  define, or a value that is not canonical under it (possible
     *                                  only when the message was built with a different spec)
     */
    public byte[] encode(IsoMessage message) {
        long primary = 0;
        long secondary = 0;
        StringBuilder body = new StringBuilder(256);
        for (Map.Entry<Integer, String> e : message.fields().entrySet()) {
            int n = e.getKey();
            String value = e.getValue();
            FieldSpec fs = spec.field(n).orElseThrow(
                    () -> new IllegalArgumentException("field " + n + " is not defined in the codec spec"));
            if (!fs.normalize(value).equals(value)) {
                throw new IllegalArgumentException("field " + n + " is not in canonical form for the codec spec");
            }
            if (!fs.isFixed()) {
                String len = Integer.toString(value.length());
                body.append("0".repeat(fs.lengthType().prefixDigits() - len.length())).append(len);
            }
            body.append(value);
            if (n <= 64) {
                primary |= 1L << (64 - n);
            } else {
                secondary |= 1L << (128 - n);
            }
        }
        if (secondary != 0) {
            primary |= 1L << 63;
        }
        StringBuilder out = new StringBuilder(MTI_LENGTH + 2 * BITMAP_LENGTH + body.length());
        out.append(message.mti().value()).append(hex(primary));
        if (secondary != 0) {
            out.append(hex(secondary));
        }
        out.append(body);
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public IsoMessage decode(byte[] data) throws IsoParseException {
        Objects.requireNonNull(data, "data");
        // ISO-8859-1 maps each byte to exactly one char, so string indices are byte offsets and
        // non-ASCII bytes survive as chars that every FieldType rejects.
        String in = new String(data, StandardCharsets.ISO_8859_1);
        Cursor cur = new Cursor(in);

        String mtiText = cur.take(IsoParseException.NO_FIELD, MTI_LENGTH);
        if (FieldType.N.firstInvalid(mtiText) >= 0) {
            throw new IsoParseException(IsoParseException.NO_FIELD, 0, "MTI is not 4 digits");
        }
        Mti mti = new Mti(mtiText);

        int primaryOffset = cur.pos;
        long primary = cur.bitmap();
        int secondaryOffset = cur.pos;
        long secondary = 0;
        if (primary < 0) {
            secondary = cur.bitmap();
            if (secondary == 0) {
                throw new IsoParseException(IsoParseException.NO_FIELD, secondaryOffset,
                        "secondary bitmap present but no fields 65-128 set");
            }
        }

        for (int n = 2; n <= 128; n++) {
            if (isSet(primary, secondary, n) && !spec.defines(n)) {
                int at = n <= 64 ? primaryOffset + (n - 1) / 4 : secondaryOffset + (n - 65) / 4;
                throw new IsoParseException(n, at, "bitmap bit set for field not in spec");
            }
        }

        TreeMap<Integer, String> fields = new TreeMap<>();
        for (int n = 2; n <= 128; n++) {
            if (isSet(primary, secondary, n)) {
                fields.put(n, readField(cur, spec.field(n).orElseThrow()));
            }
        }
        if (cur.pos != in.length()) {
            throw new IsoParseException(IsoParseException.NO_FIELD, cur.pos,
                    (in.length() - cur.pos) + " trailing bytes after last field");
        }
        return IsoMessage.ofValidated(spec, mti, fields);
    }

    /**
     * Builds the response to {@code request} (0100 to 0110, 0400 to 0410, 0800 to 0810), copying the
     * fields a response echoes (2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49, 70, 90) when present.
     *
     * @param authId field 38; {@code null} to omit it, as on a decline or a network management response
     * @throws IllegalArgumentException if the request MTI is not a request or advice, or a value is invalid
     */
    public static IsoMessage responseFor(IsoMessage request, String responseCode, String authId) {
        Mti.Function fn = request.mti().function();
        if (fn != Mti.Function.REQUEST && fn != Mti.Function.ADVICE) {
            throw new IllegalArgumentException("cannot respond to MTI " + request.mti());
        }
        IsoMessage.Builder b = IsoMessage.builder(request.spec(), request.mti().responseMti());
        request.fields().forEach((n, v) -> {
            if (ECHOED_IN_RESPONSE.contains(n)) {
                b.field(n, v);
            }
        });
        if (authId != null) {
            b.field(38, authId);
        }
        return b.field(39, responseCode).build();
    }

    private static String readField(Cursor cur, FieldSpec fs) throws IsoParseException {
        int n = fs.number();
        int fieldStart = cur.pos;
        int length = fs.length();
        if (!fs.isFixed()) {
            String prefix = cur.take(n, fs.lengthType().prefixDigits());
            if (FieldType.N.firstInvalid(prefix) >= 0) {
                throw new IsoParseException(n, fieldStart, "length prefix '" + printable(prefix) + "' is not numeric");
            }
            length = Integer.parseInt(prefix);
            if (length > fs.length()) {
                throw new IsoParseException(n, fieldStart, "length " + length + " exceeds max " + fs.length());
            }
        }
        int valueStart = cur.pos;
        String value = cur.take(n, length);
        int bad = fs.type().firstInvalid(value);
        if (bad >= 0) {
            throw new IsoParseException(n, valueStart + bad, String.format(
                    "byte 0x%02X not allowed in %s field", (int) value.charAt(bad), fs.type()));
        }
        if (fs.type() == FieldType.B) {
            if (length % 2 != 0) {
                throw new IsoParseException(n, fieldStart, "odd number of hex chars (" + length + ")");
            }
            return value.toUpperCase(Locale.ROOT);
        }
        return value;
    }

    private static boolean isSet(long primary, long secondary, int n) {
        return n <= 64 ? (primary >>> (64 - n) & 1) != 0 : (secondary >>> (128 - n) & 1) != 0;
    }

    private static String hex(long bitmap) {
        String h = Long.toHexString(bitmap).toUpperCase(Locale.ROOT);
        return "0".repeat(BITMAP_LENGTH - h.length()) + h;
    }

    private static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= 0x20 && c <= 0x7E ? String.valueOf(c) : String.format("\\x%02X", (int) c));
        }
        return sb.toString();
    }

    private static final class Cursor {
        private final String in;
        private int pos;

        Cursor(String in) {
            this.in = in;
        }

        String take(int field, int count) throws IsoParseException {
            if (in.length() - pos < count) {
                throw new IsoParseException(field, pos,
                        "truncated: need " + count + " bytes, " + (in.length() - pos) + " remain");
            }
            String s = in.substring(pos, pos + count);
            pos += count;
            return s;
        }

        long bitmap() throws IsoParseException {
            int start = pos;
            String text = take(IsoParseException.NO_FIELD, BITMAP_LENGTH);
            int bad = FieldType.B.firstInvalid(text);
            if (bad >= 0) {
                throw new IsoParseException(IsoParseException.NO_FIELD, start + bad, "bitmap is not hex");
            }
            return Long.parseUnsignedLong(text, 16);
        }
    }
}
