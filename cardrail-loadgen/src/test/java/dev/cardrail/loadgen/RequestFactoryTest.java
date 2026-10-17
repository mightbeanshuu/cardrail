package dev.cardrail.loadgen;

import dev.cardrail.iso8583.IsoCodec;
import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.Mti;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFactoryTest {

    private final RequestFactory factory =
            new RequestFactory("123456", "LG000001", "CARDRAILLOADGEN", "356", "356", ZoneId.of("Asia/Kolkata"));

    @Test
    void buildsAnEncodableAuthorizationWithConsistentTimeFields() throws Exception {
        Instant at = Instant.parse("2026-10-17T18:45:09Z");
        Card card = new Card("5555555555554444", "2912");

        IsoMessage m = factory.authorization(card, "000042", at, new SplittableRandom(1));

        assertThat(m.mti()).isEqualTo(Mti.AUTHORIZATION_REQUEST);
        assertThat(m.pan()).contains("5555555555554444");
        assertThat(m.processingCode()).contains("000000");
        assertThat(m.transmissionDateTime()).contains("1017184509");
        assertThat(m.stan()).contains("000042");
        // 18:45:09 UTC is 00:15:09 on 18 October in India (UTC+05:30).
        assertThat(m.field(12)).contains("001509");
        assertThat(m.field(13)).contains("1018");
        assertThat(m.expiry()).contains("2912");
        assertThat(RequestFactory.MCCS).contains(m.mcc().orElseThrow());
        assertThat(m.field(22).orElseThrow()).isIn("051", "071");
        assertThat(m.acquirerId()).contains("123456");
        // Year digit 6, day 290 of 2026, hour 18, then the STAN.
        assertThat(m.retrievalReferenceNumber()).contains("629018000042");
        assertThat(m.terminalId()).contains("LG000001");
        assertThat(m.field(43).orElseThrow()).hasSize(40);
        assertThat(m.currencyCode()).contains("356");
        assertThat(m.amountMinor().getAsLong())
                .isBetween(RequestFactory.MIN_AMOUNT_MINOR, RequestFactory.MAX_AMOUNT_MINOR);

        IsoCodec codec = new IsoCodec();
        assertThat(codec.decode(codec.encode(m))).isEqualTo(m);
    }

    @Test
    void amountsSpanTheRangeWithMoreSmallThanLargeTickets() {
        SplittableRandom rnd = new SplittableRandom(7);
        int belowTenPercentOfMax = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (int i = 0; i < 10_000; i++) {
            long a = RequestFactory.amount(rnd);
            min = Math.min(min, a);
            max = Math.max(max, a);
            if (a < RequestFactory.MAX_AMOUNT_MINOR / 10) {
                belowTenPercentOfMax++;
            }
        }
        assertThat(min).isGreaterThanOrEqualTo(RequestFactory.MIN_AMOUNT_MINOR);
        assertThat(max).isLessThanOrEqualTo(RequestFactory.MAX_AMOUNT_MINOR);
        // Log-uniform over [100, 500000]: P(a < 50000) = ln(500) / ln(5000), about 0.73.
        assertThat(belowTenPercentOfMax).isBetween(7_000, 7_600);
    }
}
