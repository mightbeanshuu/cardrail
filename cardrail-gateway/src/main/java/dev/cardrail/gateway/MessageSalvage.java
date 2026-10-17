package dev.cardrail.gateway;

import dev.cardrail.iso8583.FieldSpec;
import dev.cardrail.iso8583.FieldType;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.MessageSpec;
import dev.cardrail.iso8583.Mti;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Recovers what can be read from a message the codec rejected, so it can be answered with a format
 * error instead of being dropped. Fields are read in wire order and reading stops at the first one
 * that is malformed or not in the spec, because past that point field boundaries are unknown.
 */
final class MessageSalvage {

    private static final int MTI_LENGTH = 4;
    private static final int BITMAP_LENGTH = 16;

    private MessageSalvage() {
    }

    /**
     * @return the MTI and every field that precedes the first defect, or empty if the MTI is not a
     *         valid request or advice MTI or the primary bitmap is unreadable
     */
    static Optional<IsoMessage> salvage(byte[] data, MessageSpec spec) {
        String in = new String(data, StandardCharsets.ISO_8859_1);
        if (in.length() < MTI_LENGTH + BITMAP_LENGTH || !all(in, 0, MTI_LENGTH, FieldType.N)) {
            return Optional.empty();
        }
        Mti mti = new Mti(in.substring(0, MTI_LENGTH));
        Mti.Function function = mti.function();
        if (function != Mti.Function.REQUEST && function != Mti.Function.ADVICE) {
            return Optional.empty();
        }
        if (!all(in, MTI_LENGTH, BITMAP_LENGTH, FieldType.B)) {
            return Optional.empty();
        }
        long primary = Long.parseUnsignedLong(in.substring(MTI_LENGTH, MTI_LENGTH + BITMAP_LENGTH), 16);
        int pos = MTI_LENGTH + BITMAP_LENGTH;
        long secondary = 0;
        boolean secondaryKnown = true;
        if (primary < 0) {
            // A damaged secondary bitmap still has a known length, so the primary fields after it
            // remain readable; only fields 65-128 are lost.
            secondaryKnown = in.length() >= pos + BITMAP_LENGTH && all(in, pos, BITMAP_LENGTH, FieldType.B);
            if (secondaryKnown) {
                secondary = Long.parseUnsignedLong(in.substring(pos, pos + BITMAP_LENGTH), 16);
            }
            pos += BITMAP_LENGTH;
        }

        IsoMessage.Builder out = IsoMessage.builder(spec, mti);
        int last = secondaryKnown ? 128 : 64;
        for (int n = 2; n <= last; n++) {
            boolean set = n <= 64 ? (primary >>> (64 - n) & 1) != 0 : (secondary >>> (128 - n) & 1) != 0;
            if (!set) {
                continue;
            }
            Optional<FieldSpec> fs = spec.field(n);
            if (fs.isEmpty()) {
                break;
            }
            int length = fs.get().length();
            int prefix = fs.get().lengthType().prefixDigits();
            if (prefix > 0) {
                if (in.length() < pos + prefix || !all(in, pos, prefix, FieldType.N)) {
                    break;
                }
                length = Integer.parseInt(in.substring(pos, pos + prefix));
                pos += prefix;
            }
            if (in.length() < pos + length) {
                break;
            }
            String value = in.substring(pos, pos + length);
            pos += length;
            try {
                out.field(n, value);
            } catch (IllegalArgumentException e) {
                break;
            }
        }
        return Optional.of(out.build());
    }

    private static boolean all(String s, int from, int count, FieldType type) {
        for (int i = from; i < from + count; i++) {
            if (!type.accepts(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
