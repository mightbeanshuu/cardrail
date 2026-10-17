package dev.cardrail.iso8583;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * An immutable ISO 8583 message: an MTI plus data elements in canonical wire form (fixed fields
 * already padded, hex uppercased), keyed and iterated by field number.
 *
 * <p>Every value is validated against the {@link MessageSpec} the message was built with, so an
 * {@code IsoMessage} that exists is always encodable by a codec using the same spec.
 */
public final class IsoMessage {

    /** Track data, PIN block and ICC data never appear in {@link #toString()}. */
    private static final Set<Integer> REDACTED = Set.of(35, 36, 45, 52, 55);

    private final MessageSpec spec;
    private final Mti mti;
    private final SortedMap<Integer, String> fields;

    private IsoMessage(MessageSpec spec, Mti mti, SortedMap<Integer, String> fields) {
        this.spec = spec;
        this.mti = mti;
        this.fields = Collections.unmodifiableSortedMap(fields);
    }

    /** For the codec, which has already validated every value against {@code spec}. */
    static IsoMessage ofValidated(MessageSpec spec, Mti mti, SortedMap<Integer, String> fields) {
        return new IsoMessage(spec, mti, fields);
    }

    public static Builder builder(Mti mti) {
        return new Builder(MessageSpec.defaultSpec(), mti);
    }

    public static Builder builder(MessageSpec spec, Mti mti) {
        return new Builder(spec, mti);
    }

    public Mti mti() {
        return mti;
    }

    public MessageSpec spec() {
        return spec;
    }

    /** All present fields, ascending by number. */
    public SortedMap<Integer, String> fields() {
        return fields;
    }

    public boolean has(int number) {
        return fields.containsKey(number);
    }

    public Optional<String> field(int number) {
        return Optional.ofNullable(fields.get(number));
    }

    /** Returns a copy with {@code number} set (validated and normalised) or replaced. */
    public IsoMessage withField(int number, String value) {
        return toBuilder().field(number, value).build();
    }

    public IsoMessage withoutField(int number) {
        return toBuilder().remove(number).build();
    }

    public Builder toBuilder() {
        Builder b = new Builder(spec, mti);
        b.fields.putAll(fields);
        return b;
    }

    public Optional<String> pan() {
        return field(2);
    }

    public Optional<String> processingCode() {
        return field(3);
    }

    /** Field 4 in minor units of the field 49 currency. */
    public OptionalLong amountMinor() {
        String v = fields.get(4);
        return v == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(v));
    }

    public Optional<String> transmissionDateTime() {
        return field(7);
    }

    public Optional<String> stan() {
        return field(11);
    }

    public Optional<String> expiry() {
        return field(14);
    }

    public Optional<String> mcc() {
        return field(18);
    }

    public Optional<String> acquirerId() {
        return field(32);
    }

    public Optional<String> retrievalReferenceNumber() {
        return field(37);
    }

    public Optional<String> authorizationId() {
        return field(38);
    }

    public Optional<String> responseCode() {
        return field(39);
    }

    public Optional<String> terminalId() {
        return field(41);
    }

    public Optional<String> merchantId() {
        return field(42);
    }

    public Optional<String> currencyCode() {
        return field(49);
    }

    /** Field 55 as hex; decode with {@link dev.cardrail.iso8583.emv.BerTlv#decodeHex(String)}. */
    public Optional<String> iccData() {
        return field(55);
    }

    public Optional<String> networkManagementCode() {
        return field(70);
    }

    /** Field 90 parsed. Always parseable when present under the default spec (n42). */
    public Optional<OriginalDataElements> originalDataElements() {
        return field(90).map(OriginalDataElements::parse);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IsoMessage other && mti.equals(other.mti) && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return 31 * mti.hashCode() + fields.hashCode();
    }

    /** Log-safe rendering: PAN masked to first 6 + last 4, sensitive fields reduced to their length. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("IsoMessage{mti=").append(mti);
        for (Map.Entry<Integer, String> e : fields.entrySet()) {
            int n = e.getKey();
            String v = e.getValue();
            sb.append(", ").append(n).append('=');
            if (n == 2) {
                sb.append(maskPan(v));
            } else if (REDACTED.contains(n)) {
                sb.append("<redacted ").append(v.length()).append(" chars>");
            } else {
                sb.append('\'').append(v).append('\'');
            }
        }
        return sb.append('}').toString();
    }

    // Below 13 digits, first-6 + last-4 would leave fewer than 3 digits hidden, so mask everything.
    private static String maskPan(String pan) {
        if (pan.length() < 13) {
            return "*".repeat(pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    public static final class Builder {
        private final MessageSpec spec;
        private final Mti mti;
        private final TreeMap<Integer, String> fields = new TreeMap<>();

        private Builder(MessageSpec spec, Mti mti) {
            this.spec = Objects.requireNonNull(spec, "spec");
            this.mti = Objects.requireNonNull(mti, "mti");
        }

        /**
         * Sets a field after validating it against the spec.
         *
         * @throws IllegalArgumentException if the field is not in the spec or the value is invalid
         */
        public Builder field(int number, String value) {
            FieldSpec fs = spec.field(number).orElseThrow(
                    () -> new IllegalArgumentException("field " + number + " is not defined in the message spec"));
            fields.put(number, fs.normalize(value));
            return this;
        }

        public Builder remove(int number) {
            fields.remove(number);
            return this;
        }

        public IsoMessage build() {
            return new IsoMessage(spec, mti, new TreeMap<>(fields));
        }
    }
}
