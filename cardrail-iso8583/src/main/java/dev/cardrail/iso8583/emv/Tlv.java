package dev.cardrail.iso8583.emv;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * One BER-TLV data object. Tags are held as their encoded bytes packed big-endian into an int
 * ({@code 0x9F26}, {@code 0x82}), which is how EMV specifications write them.
 */
public sealed interface Tlv permits Tlv.Primitive, Tlv.Constructed {

    int tag();

    static Primitive primitive(int tag, byte[] value) {
        return new Primitive(tag, value);
    }

    static Constructed constructed(int tag, List<Tlv> children) {
        return new Constructed(tag, children);
    }

    /** True when bit 6 of the tag's first byte is set, meaning the value is itself a TLV sequence. */
    static boolean isConstructedTag(int tag) {
        return (Tags.firstByte(tag) & 0x20) != 0;
    }

    /** Tag rendered as uppercase hex, e.g. {@code 9F26}. */
    static String tagHex(int tag) {
        return HexFormat.of().withUpperCase().formatHex(Tags.bytes(tag));
    }

    record Primitive(int tag, byte[] value) implements Tlv {
        public Primitive {
            Tags.validate(tag);
            if (isConstructedTag(tag)) {
                throw new IllegalArgumentException("tag " + tagHex(tag) + " is constructed");
            }
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        public String valueHex() {
            return HexFormat.of().withUpperCase().formatHex(value);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Primitive p && tag == p.tag && Arrays.equals(value, p.value);
        }

        @Override
        public int hashCode() {
            return 31 * tag + Arrays.hashCode(value);
        }

        /** Values of tags carrying card data (PAN, track 2 equivalent) are reduced to their length. */
        @Override
        public String toString() {
            boolean sensitive = EmvTag.of(tag).map(EmvTag::sensitive).orElse(false);
            return tagHex(tag) + "=" + (sensitive ? "<" + value.length + " bytes>" : valueHex());
        }
    }

    record Constructed(int tag, List<Tlv> children) implements Tlv {
        public Constructed {
            Tags.validate(tag);
            if (!isConstructedTag(tag)) {
                throw new IllegalArgumentException("tag " + tagHex(tag) + " is primitive");
            }
            children = List.copyOf(children);
        }

        @Override
        public String toString() {
            return tagHex(tag) + children;
        }
    }
}
