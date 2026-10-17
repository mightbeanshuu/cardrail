package dev.cardrail.iso8583;

import java.util.Objects;

/**
 * Message type indicator: four ASCII digits, version / class / function / origin.
 *
 * <p>Any four digits are accepted, so the codec can decode MTIs it has no business logic for and
 * leave the decision (typically response code 12) to the caller.
 */
public record Mti(String value) {

    public static final Mti AUTHORIZATION_REQUEST = new Mti("0100");
    public static final Mti AUTHORIZATION_RESPONSE = new Mti("0110");
    public static final Mti REVERSAL_REQUEST = new Mti("0400");
    public static final Mti REVERSAL_RESPONSE = new Mti("0410");
    public static final Mti NETWORK_MANAGEMENT_REQUEST = new Mti("0800");
    public static final Mti NETWORK_MANAGEMENT_RESPONSE = new Mti("0810");

    public Mti {
        Objects.requireNonNull(value, "value");
        if (value.length() != 4 || FieldType.N.firstInvalid(value) >= 0) {
            throw new IllegalArgumentException("MTI must be 4 digits, got '" + value + "'");
        }
    }

    public static Mti of(String value) {
        return new Mti(value);
    }

    public Version version() {
        return Version.of(digit(0));
    }

    public MessageClass messageClass() {
        return MessageClass.of(digit(1));
    }

    public Function function() {
        return Function.of(digit(2));
    }

    public Origin origin() {
        return Origin.of(digit(3));
    }

    public boolean isRequest() {
        return function() == Function.REQUEST;
    }

    /**
     * The MTI that answers this one: requests become request responses ({@code 0100 -> 0110}) and
     * advices become advice responses ({@code 0120 -> 0130}). A repeat ({@code 0101}) is answered with
     * the ordinary response MTI, because the responder cannot tell which transmission it is answering.
     *
     * @throws IllegalStateException if this MTI is not a request or an advice
     */
    public Mti responseMti() {
        int fn = digit(2);
        if (fn != 0 && fn != 2) {
            throw new IllegalStateException("MTI " + value + " is not a request or advice");
        }
        int origin = digit(3);
        int responseOrigin = origin <= 5 ? origin & ~1 : origin;
        return new Mti("" + value.charAt(0) + value.charAt(1) + (fn + 1) + responseOrigin);
    }

    private int digit(int index) {
        return value.charAt(index) - '0';
    }

    @Override
    public String toString() {
        return value;
    }

    public enum Version {
        ISO_8583_1987, ISO_8583_1993, ISO_8583_2003, RESERVED, NATIONAL, PRIVATE;

        static Version of(int digit) {
            return switch (digit) {
                case 0 -> ISO_8583_1987;
                case 1 -> ISO_8583_1993;
                case 2 -> ISO_8583_2003;
                case 8 -> NATIONAL;
                case 9 -> PRIVATE;
                default -> RESERVED;
            };
        }
    }

    public enum MessageClass {
        AUTHORIZATION, FINANCIAL, FILE_ACTION, REVERSAL_OR_CHARGEBACK, RECONCILIATION, ADMINISTRATIVE,
        FEE_COLLECTION, NETWORK_MANAGEMENT, RESERVED;

        static MessageClass of(int digit) {
            return switch (digit) {
                case 1 -> AUTHORIZATION;
                case 2 -> FINANCIAL;
                case 3 -> FILE_ACTION;
                case 4 -> REVERSAL_OR_CHARGEBACK;
                case 5 -> RECONCILIATION;
                case 6 -> ADMINISTRATIVE;
                case 7 -> FEE_COLLECTION;
                case 8 -> NETWORK_MANAGEMENT;
                default -> RESERVED;
            };
        }
    }

    public enum Function {
        REQUEST, REQUEST_RESPONSE, ADVICE, ADVICE_RESPONSE, NOTIFICATION, NOTIFICATION_ACKNOWLEDGEMENT,
        INSTRUCTION, INSTRUCTION_ACKNOWLEDGEMENT, RESERVED;

        static Function of(int digit) {
            return digit <= 7 ? values()[digit] : RESERVED;
        }
    }

    public enum Origin {
        ACQUIRER, ACQUIRER_REPEAT, ISSUER, ISSUER_REPEAT, OTHER, OTHER_REPEAT, RESERVED;

        static Origin of(int digit) {
            return digit <= 5 ? values()[digit] : RESERVED;
        }
    }
}
