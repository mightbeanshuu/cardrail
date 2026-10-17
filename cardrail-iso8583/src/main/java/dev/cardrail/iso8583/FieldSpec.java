package dev.cardrail.iso8583;

import java.util.Locale;
import java.util.Objects;

/**
 * Definition of one data element.
 *
 * @param number     field number, 2-128 (bit 1 is the secondary-bitmap indicator, not a field)
 * @param name       human-readable name, used in error messages only
 * @param type       character class
 * @param lengthType fixed or LL/LLL variable
 * @param length     exact length for {@link LengthType#FIXED}, maximum length otherwise
 */
public record FieldSpec(int number, String name, FieldType type, LengthType lengthType, int length) {

    public FieldSpec {
        if (number < 2 || number > 128) {
            throw new IllegalArgumentException("field number must be 2-128, got " + number);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lengthType, "lengthType");
        if (length < 1 || length > lengthType.prefixCapacity()) {
            throw new IllegalArgumentException(
                    "field " + number + ": length " + length + " not expressible as " + lengthType);
        }
        if (type == FieldType.B && lengthType == LengthType.FIXED && length % 2 != 0) {
            throw new IllegalArgumentException("field " + number + ": fixed binary length must be even hex chars");
        }
    }

    public static FieldSpec fixed(int number, String name, FieldType type, int length) {
        return new FieldSpec(number, name, type, LengthType.FIXED, length);
    }

    public static FieldSpec llvar(int number, String name, FieldType type, int maxLength) {
        return new FieldSpec(number, name, type, LengthType.LLVAR, maxLength);
    }

    public static FieldSpec lllvar(int number, String name, FieldType type, int maxLength) {
        return new FieldSpec(number, name, type, LengthType.LLLVAR, maxLength);
    }

    public boolean isFixed() {
        return lengthType == LengthType.FIXED;
    }

    /**
     * Validates {@code value} and returns its canonical wire form: fixed N fields left-zero-padded,
     * fixed AN/ANS fields right-space-padded, hex uppercased. Values longer than the spec allows are
     * rejected rather than truncated, since silently cutting an amount or PAN is never correct.
     *
     * @throws IllegalArgumentException if the value cannot be represented in this field
     */
    public String normalize(String value) {
        Objects.requireNonNull(value, () -> "field " + number + " value");
        int bad = type.firstInvalid(value);
        if (bad >= 0) {
            throw new IllegalArgumentException(
                    "field " + number + " (" + name + "): character at index " + bad + " not allowed for " + type);
        }
        if (value.length() > length) {
            throw new IllegalArgumentException("field " + number + " (" + name + "): length " + value.length()
                    + " exceeds " + (isFixed() ? "fixed length " : "max length ") + length);
        }
        if (type == FieldType.B) {
            if (value.length() % 2 != 0) {
                throw new IllegalArgumentException("field " + number + " (" + name + "): odd number of hex chars");
            }
            if (isFixed() && value.length() != length) {
                throw new IllegalArgumentException("field " + number + " (" + name + "): binary field needs exactly "
                        + length + " hex chars");
            }
            return value.toUpperCase(Locale.ROOT);
        }
        if (!isFixed() || value.length() == length) {
            return value;
        }
        String pad = (type == FieldType.N ? "0" : " ").repeat(length - value.length());
        return type == FieldType.N ? pad + value : value + pad;
    }

    /** Spec-style format string, e.g. {@code n6}, {@code n..19}, {@code ans...999}. */
    public String format() {
        String t = type.name().toLowerCase(Locale.ROOT);
        return switch (lengthType) {
            case FIXED -> t + length;
            case LLVAR -> t + ".." + length;
            case LLLVAR -> t + "..." + length;
        };
    }
}
