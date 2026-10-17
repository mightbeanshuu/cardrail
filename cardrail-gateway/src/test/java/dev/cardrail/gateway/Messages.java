package dev.cardrail.gateway;

import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.Mti;

final class Messages {

    private Messages() {
    }

    static IsoMessage authorization(int stan, long amountMinor) {
        String s = String.format("%06d", stan);
        return IsoMessage.builder(Mti.AUTHORIZATION_REQUEST)
                .field(2, "5555555555554444")
                .field(3, "000000")
                .field(4, Long.toString(amountMinor))
                .field(7, "1017120000")
                .field(11, s)
                .field(12, "120000")
                .field(13, "1017")
                .field(14, "2912")
                .field(18, "5411")
                .field(22, "051")
                .field(32, "123456")
                .field(37, "6290120" + s.substring(1))
                .field(41, "TERM0001")
                .field(42, "MERCHANT0000001")
                .field(49, "840")
                .build();
    }

    static IsoMessage echo(int stan) {
        return IsoMessage.builder(Mti.NETWORK_MANAGEMENT_REQUEST)
                .field(7, "1017120000")
                .field(11, String.format("%06d", stan))
                .field(70, "301")
                .build();
    }
}
