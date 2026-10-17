package dev.cardrail.iso8583.emv;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Names for the EMV tags commonly found in field 55 of an authorization request. */
public enum EmvTag {
    APPLICATION_PAN(0x5A, "Application Primary Account Number", true),
    TRACK2_EQUIVALENT_DATA(0x57, "Track 2 Equivalent Data", true),
    TRANSACTION_CURRENCY_CODE(0x5F2A, "Transaction Currency Code", false),
    APPLICATION_INTERCHANGE_PROFILE(0x82, "Application Interchange Profile", false),
    DEDICATED_FILE_NAME(0x84, "Dedicated File (DF) Name", false),
    TERMINAL_VERIFICATION_RESULTS(0x95, "Terminal Verification Results", false),
    TRANSACTION_DATE(0x9A, "Transaction Date", false),
    TRANSACTION_TYPE(0x9C, "Transaction Type", false),
    AMOUNT_AUTHORISED(0x9F02, "Amount, Authorised (Numeric)", false),
    AMOUNT_OTHER(0x9F03, "Amount, Other (Numeric)", false),
    APPLICATION_VERSION_NUMBER(0x9F09, "Application Version Number (Terminal)", false),
    ISSUER_APPLICATION_DATA(0x9F10, "Issuer Application Data", false),
    TERMINAL_COUNTRY_CODE(0x9F1A, "Terminal Country Code", false),
    IFD_SERIAL_NUMBER(0x9F1E, "Interface Device (IFD) Serial Number", false),
    APPLICATION_CRYPTOGRAM(0x9F26, "Application Cryptogram", false),
    CRYPTOGRAM_INFORMATION_DATA(0x9F27, "Cryptogram Information Data", false),
    TERMINAL_CAPABILITIES(0x9F33, "Terminal Capabilities", false),
    CVM_RESULTS(0x9F34, "Cardholder Verification Method Results", false),
    TERMINAL_TYPE(0x9F35, "Terminal Type", false),
    APPLICATION_TRANSACTION_COUNTER(0x9F36, "Application Transaction Counter", false),
    UNPREDICTABLE_NUMBER(0x9F37, "Unpredictable Number", false);

    private static final Map<Integer, EmvTag> BY_TAG =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(EmvTag::tag, Function.identity()));

    private final int tag;
    private final String description;
    private final boolean sensitive;

    EmvTag(int tag, String description, boolean sensitive) {
        this.tag = tag;
        this.description = description;
        this.sensitive = sensitive;
    }

    public static Optional<EmvTag> of(int tag) {
        return Optional.ofNullable(BY_TAG.get(tag));
    }

    public int tag() {
        return tag;
    }

    public String description() {
        return description;
    }

    /** Carries cardholder data that must not be logged. */
    public boolean sensitive() {
        return sensitive;
    }
}
