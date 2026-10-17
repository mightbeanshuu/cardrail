package dev.cardrail.sw.domain.authorization;

import java.util.Arrays;

/** ISO 8583 field 39 values used by the switch (see SPEC.md). */
public enum ResponseCode {
    APPROVED("00"),
    DO_NOT_HONOR("05"),
    INVALID_TRANSACTION("12"),
    INVALID_AMOUNT("13"),
    INVALID_CARD_NUMBER("14"),
    FORMAT_ERROR("30"),
    INSUFFICIENT_FUNDS("51"),
    EXPIRED_CARD("54"),
    NOT_PERMITTED_TO_CARDHOLDER("57"),
    EXCEEDS_AMOUNT_LIMIT("61"),
    EXCEEDS_FREQUENCY_LIMIT("65"),
    ISSUER_UNAVAILABLE("91"),
    DUPLICATE_TRANSMISSION("94"),
    SYSTEM_MALFUNCTION("96");

    private final String code;

    ResponseCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean isApproved() {
        return this == APPROVED;
    }

    public static ResponseCode fromCode(String code) {
        return Arrays.stream(values())
                .filter(rc -> rc.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown response code " + code));
    }
}
