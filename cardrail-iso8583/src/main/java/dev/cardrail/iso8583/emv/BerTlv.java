package dev.cardrail.iso8583.emv;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * BER-TLV codec for EMV data (EMV Book 3 Annex B): multi-byte tags, short and long-form lengths
 * ({@code 81 xx}, {@code 82 xx xx}), and constructed objects parsed recursively.
 */
public final class BerTlv {

    /** EMV templates nest two or three levels; anything deeper is malformed or hostile. */
    private static final int MAX_DEPTH = 8;
    private static final int MAX_TAG_BYTES = 4;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private BerTlv() {
    }

    public static List<Tlv> decode(byte[] data) throws TlvParseException {
        return parse(data, 0, data.length, 0);
    }

    /**
     * Decodes field 55 as carried in the ISO message (hex ASCII).
     *
     * @throws IllegalArgumentException if {@code hex} is not an even-length hex string
     */
    public static List<Tlv> decodeHex(String hex) throws TlvParseException {
        return decode(HexFormat.of().parseHex(hex));
    }

    /** @throws IllegalArgumentException if a value is longer than a two-byte long-form length allows */
    public static byte[] encode(List<Tlv> tlvs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Tlv tlv : tlvs) {
            write(out, tlv);
        }
        return out.toByteArray();
    }

    /** Encodes to uppercase hex, the form field 55 takes inside the ISO message. */
    public static String encodeHex(List<Tlv> tlvs) {
        return HEX.formatHex(encode(tlvs));
    }

    /** Depth-first search, descending into constructed objects. */
    public static Optional<Tlv> find(List<Tlv> tlvs, int tag) {
        for (Tlv tlv : tlvs) {
            if (tlv.tag() == tag) {
                return Optional.of(tlv);
            }
            if (tlv instanceof Tlv.Constructed c) {
                Optional<Tlv> nested = find(c.children(), tag);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static List<Tlv> parse(byte[] d, int from, int to, int depth) throws TlvParseException {
        List<Tlv> result = new ArrayList<>();
        int pos = from;
        while (pos < to) {
            int start = pos;
            int first = d[pos++] & 0xFF;
            int tag = first;
            if ((first & 0x1F) == 0x1F) {
                int b;
                int tagBytes = 1;
                do {
                    if (pos >= to) {
                        throw new TlvParseException(start, "tag truncated");
                    }
                    if (++tagBytes > MAX_TAG_BYTES) {
                        throw new TlvParseException(start, "tag longer than " + MAX_TAG_BYTES + " bytes");
                    }
                    b = d[pos++] & 0xFF;
                    tag = tag << 8 | b;
                } while ((b & 0x80) != 0);
            }

            if (pos >= to) {
                throw new TlvParseException(start, "missing length");
            }
            int lengthAt = pos;
            int l = d[pos++] & 0xFF;
            int length;
            if (l < 0x80) {
                length = l;
            } else if (l == 0x81 || l == 0x82) {
                int n = l & 0x7F;
                if (to - pos < n) {
                    throw new TlvParseException(lengthAt, "long-form length truncated");
                }
                length = 0;
                for (int i = 0; i < n; i++) {
                    length = length << 8 | (d[pos++] & 0xFF);
                }
            } else if (l == 0x80) {
                throw new TlvParseException(lengthAt, "indefinite length is not allowed in EMV");
            } else {
                throw new TlvParseException(lengthAt, String.format("unsupported length form 0x%02X", l));
            }
            if (length > to - pos) {
                throw new TlvParseException(start, "length " + length + " exceeds remaining " + (to - pos) + " bytes");
            }

            if ((first & 0x20) != 0) {
                if (depth >= MAX_DEPTH) {
                    throw new TlvParseException(start, "nesting deeper than " + MAX_DEPTH);
                }
                result.add(new Tlv.Constructed(tag, parse(d, pos, pos + length, depth + 1)));
            } else {
                byte[] value = new byte[length];
                System.arraycopy(d, pos, value, 0, length);
                result.add(new Tlv.Primitive(tag, value));
            }
            pos += length;
        }
        return result;
    }

    private static void write(ByteArrayOutputStream out, Tlv tlv) {
        byte[] value = switch (tlv) {
            case Tlv.Primitive p -> p.value();
            case Tlv.Constructed c -> encode(c.children());
        };
        out.writeBytes(Tags.bytes(tlv.tag()));
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n <= 0xFF) {
            out.write(0x81);
            out.write(n);
        } else if (n <= 0xFFFF) {
            out.write(0x82);
            out.write(n >>> 8);
            out.write(n & 0xFF);
        } else {
            throw new IllegalArgumentException("value of tag " + Tlv.tagHex(tlv.tag()) + " too long: " + n + " bytes");
        }
        out.writeBytes(value);
    }
}
