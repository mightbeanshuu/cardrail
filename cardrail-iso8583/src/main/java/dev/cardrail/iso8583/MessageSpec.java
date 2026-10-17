package dev.cardrail.iso8583;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static dev.cardrail.iso8583.FieldType.AN;
import static dev.cardrail.iso8583.FieldType.ANS;
import static dev.cardrail.iso8583.FieldType.B;
import static dev.cardrail.iso8583.FieldType.N;

/** The set of fields a codec understands. Fields outside the spec are rejected on encode and decode. */
public final class MessageSpec {

    private static final MessageSpec DEFAULT = builder()
            .field(FieldSpec.llvar(2, "Primary account number", N, 19))
            .field(FieldSpec.fixed(3, "Processing code", N, 6))
            .field(FieldSpec.fixed(4, "Amount, transaction", N, 12))
            .field(FieldSpec.fixed(7, "Transmission date and time", N, 10))
            .field(FieldSpec.fixed(11, "System trace audit number", N, 6))
            .field(FieldSpec.fixed(12, "Local transaction time", N, 6))
            .field(FieldSpec.fixed(13, "Local transaction date", N, 4))
            .field(FieldSpec.fixed(14, "Expiration date", N, 4))
            .field(FieldSpec.fixed(18, "Merchant category code", N, 4))
            .field(FieldSpec.fixed(19, "Acquiring institution country code", N, 3))
            .field(FieldSpec.fixed(22, "POS entry mode", N, 3))
            .field(FieldSpec.llvar(32, "Acquiring institution ID", N, 11))
            .field(FieldSpec.fixed(37, "Retrieval reference number", AN, 12))
            .field(FieldSpec.fixed(38, "Authorization ID response", AN, 6))
            .field(FieldSpec.fixed(39, "Response code", AN, 2))
            .field(FieldSpec.fixed(41, "Card acceptor terminal ID", ANS, 8))
            .field(FieldSpec.fixed(42, "Card acceptor ID", ANS, 15))
            .field(FieldSpec.fixed(43, "Card acceptor name/location", ANS, 40))
            .field(FieldSpec.fixed(49, "Currency code, transaction", N, 3))
            .field(FieldSpec.lllvar(55, "ICC data", B, 999))
            .field(FieldSpec.fixed(70, "Network management information code", N, 3))
            .field(FieldSpec.fixed(90, "Original data elements", N, 42))
            .build();

    private final SortedMap<Integer, FieldSpec> fields;

    private MessageSpec(SortedMap<Integer, FieldSpec> fields) {
        this.fields = Collections.unmodifiableSortedMap(new TreeMap<>(fields));
    }

    /** The field set defined in {@code docs/SPEC.md}. */
    public static MessageSpec defaultSpec() {
        return DEFAULT;
    }

    public static Builder builder() {
        return new Builder(new TreeMap<>());
    }

    /** Starts from an existing spec, e.g. to add or redefine fields on top of the default. */
    public static Builder builder(MessageSpec base) {
        return new Builder(new TreeMap<>(base.fields));
    }

    public Optional<FieldSpec> field(int number) {
        return Optional.ofNullable(fields.get(number));
    }

    public boolean defines(int number) {
        return fields.containsKey(number);
    }

    public Collection<FieldSpec> fields() {
        return fields.values();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MessageSpec other && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return "MessageSpec" + fields.keySet();
    }

    public static final class Builder {
        private final TreeMap<Integer, FieldSpec> fields;

        private Builder(TreeMap<Integer, FieldSpec> fields) {
            this.fields = fields;
        }

        /** Adds a field, replacing any existing definition with the same number. */
        public Builder field(FieldSpec spec) {
            Objects.requireNonNull(spec, "spec");
            fields.put(spec.number(), spec);
            return this;
        }

        public Builder remove(int number) {
            fields.remove(number);
            return this;
        }

        public MessageSpec build() {
            return new MessageSpec(fields);
        }
    }
}
