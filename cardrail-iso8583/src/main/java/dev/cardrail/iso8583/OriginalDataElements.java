package dev.cardrail.iso8583;

import java.util.Objects;

/**
 * Field 90, n42: identifies the transaction a reversal refers to.
 * Layout: original MTI (4) + original STAN (6) + original field 7 (10) + original acquirer ID (11)
 * + forwarding institution ID (11). The two IDs are right-justified and zero-padded.
 */
public record OriginalDataElements(Mti originalMti, String originalStan, String originalTransmissionDateTime,
                                   String originalAcquirerId, String forwardingInstitutionId) {

    private static final int LENGTH = 42;

    public OriginalDataElements {
        Objects.requireNonNull(originalMti, "originalMti");
        originalStan = digits("originalStan", originalStan, 6);
        originalTransmissionDateTime = digits("originalTransmissionDateTime", originalTransmissionDateTime, 10);
        originalAcquirerId = digits("originalAcquirerId", originalAcquirerId, 11);
        forwardingInstitutionId = digits("forwardingInstitutionId", forwardingInstitutionId, 11);
    }

    /** Parses a field 90 value. */
    public static OriginalDataElements parse(String field90) {
        if (field90.length() != LENGTH || FieldType.N.firstInvalid(field90) >= 0) {
            throw new IllegalArgumentException("field 90 must be " + LENGTH + " digits");
        }
        return new OriginalDataElements(new Mti(field90.substring(0, 4)), field90.substring(4, 10),
                field90.substring(10, 20), field90.substring(20, 31), field90.substring(31, 42));
    }

    /** Builds field 90 from an original request; forwarding institution is zeros when not forwarded. */
    public static OriginalDataElements of(IsoMessage original) {
        return new OriginalDataElements(original.mti(),
                original.stan().orElseThrow(() -> new IllegalArgumentException("original has no field 11")),
                original.transmissionDateTime()
                        .orElseThrow(() -> new IllegalArgumentException("original has no field 7")),
                original.acquirerId().orElse("0"), "0");
    }

    public String toField() {
        return originalMti.value() + originalStan + originalTransmissionDateTime + originalAcquirerId
                + forwardingInstitutionId;
    }

    private static String digits(String name, String value, int width) {
        Objects.requireNonNull(value, name);
        if (value.length() > width || FieldType.N.firstInvalid(value) >= 0) {
            throw new IllegalArgumentException(name + " must be at most " + width + " digits");
        }
        return "0".repeat(width - value.length()) + value;
    }
}
