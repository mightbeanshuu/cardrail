package dev.cardrail.sw.integration;

import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.Mti;
import dev.cardrail.iso8583.OriginalDataElements;
import dev.cardrail.loadgen.AcquirerClient;
import dev.cardrail.loadgen.Card;
import dev.cardrail.loadgen.CardPool;
import dev.cardrail.loadgen.RequestFactory;
import dev.cardrail.sw.adapter.iso.IsoGatewayLifecycle;
import dev.cardrail.sw.domain.authorization.AuthorizationStatus;
import dev.cardrail.sw.domain.authorization.StoredAuthorization;
import dev.cardrail.sw.domain.authorization.TransmissionKey;
import dev.cardrail.sw.port.AccountBalance;
import dev.cardrail.sw.port.AuthorizationRepository;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole path a terminal's request takes: ISO 8583 over a real TCP connection to the gateway,
 * the vault-backed tokenizer, the ledger in PostgreSQL. Requests are built by the load generator's
 * acquirer, not by the switch's own code.
 */
@CardrailIntegrationTest
class IsoTcpEndToEndTest {

    private static final String ACQUIRER = "40001";
    private static final String TERMINAL = "E2E00001";
    private static final DateTimeFormatter F7 = DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);

    @Autowired TestFixtures fixtures;
    @Autowired IsoGatewayLifecycle gateway;
    @Autowired AuthorizationRepository authorizations;
    @Autowired MeterRegistry meters;

    private final RequestFactory requests = new RequestFactory(ACQUIRER, TERMINAL, "MERCHANT0000001", "356", "356",
            ZoneOffset.UTC);
    private final SplittableRandom random = new SplittableRandom(7);
    private Card card;
    private AcquirerClient acquirer;
    private Instant clock;

    @BeforeEach
    void connect() throws IOException {
        fixtures.reset();
        card = CardPool.generated(1, 20261018L, YearMonth.now()).get(0);
        fixtures.openAccount(card.pan(), 1_000_000);   // INR 10,000.00
        acquirer = AcquirerClient.connect("127.0.0.1", gateway.port(), Duration.ofSeconds(10));
        clock = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @AfterEach
    void disconnect() {
        acquirer.close();
    }

    @Test
    void approvalsDeclineRetransmissionAndReversalOverTcp() throws Exception {
        IsoMessage first = purchase("000101", 400_000);
        IsoMessage second = purchase("000102", 500_000);
        IsoMessage tooMuch = purchase("000103", 200_000);

        IsoMessage firstResponse = send(first);
        IsoMessage secondResponse = send(second);
        IsoMessage declined = send(tooMuch);

        assertThat(firstResponse.mti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
        assertThat(firstResponse.responseCode()).contains("00");
        assertThat(firstResponse.authorizationId()).isPresent();
        assertThat(firstResponse.retrievalReferenceNumber()).isEqualTo(first.retrievalReferenceNumber());
        assertThat(secondResponse.responseCode()).contains("00");
        assertThat(declined.responseCode()).contains("51");
        assertThat(declined.has(38)).isFalse();
        assertBalance(100_000, 900_000);

        // The terminal did not get the first answer and sends the same message again.
        IsoMessage replayed = send(first);
        assertThat(replayed.responseCode()).contains("00");
        assertThat(replayed.authorizationId()).isEqualTo(firstResponse.authorizationId());
        assertBalance(100_000, 900_000);

        IsoMessage reversal = reversalOf(second, "000104");
        IsoMessage reversalResponse = send(reversal);
        assertThat(reversalResponse.mti()).isEqualTo(Mti.REVERSAL_RESPONSE);
        assertThat(reversalResponse.responseCode()).contains("00");
        assertBalance(600_000, 400_000);

        // A repeated reversal is acknowledged again and releases nothing more.
        assertThat(send(reversalOf(second, "000105")).responseCode()).contains("00");
        assertBalance(600_000, 400_000);
        assertThat(fixtures.unbalancedJournals()).isEmpty();

        StoredAuthorization stored = authorizations.findByKey(new TransmissionKey(ACQUIRER, "000102",
                second.transmissionDateTime().orElseThrow(), TERMINAL)).orElseThrow();
        assertThat(stored.status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(stored.token()).hasSize(16).startsWith("999999").isEqualTo(fixtures.token(card.pan()));
        assertThat(stored.maskedPan()).isEqualTo(card.pan().substring(0, 6) + "******" + card.pan().substring(12));
        assertThat(fixtures.jdbc().sql("SELECT count(*) FROM vault_record WHERE token = :token")
                .param("token", stored.token()).query(Long.class).single()).isOne();

        Timer approvals = meters.find("cardrail.iso.requests").tags("mti", "0100", "response_code", "00").timer();
        assertThat(approvals).isNotNull();
        assertThat(approvals.count()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void reversalOfAnUnknownOriginalIsInvalidTransaction() throws Exception {
        IsoMessage neverSent = purchase("000201", 10_000);

        assertThat(send(reversalOf(neverSent, "000202")).responseCode()).contains("12");
        assertBalance(1_000_000, 0);
    }

    @Test
    void echoIsAnsweredAndUnsupportedMessagesGet12() throws Exception {
        IsoMessage echo = IsoMessage.builder(Mti.NETWORK_MANAGEMENT_REQUEST)
                .field(7, F7.format(clock)).field(11, "000301").field(70, "301").build();
        IsoMessage financial = IsoMessage.builder(Mti.of("0200"))
                .field(7, F7.format(clock)).field(11, "000302").field(41, TERMINAL).build();

        assertThat(send(echo).responseCode()).contains("00");
        IsoMessage unsupported = send(financial);
        assertThat(unsupported.mti()).isEqualTo(Mti.of("0210"));
        assertThat(unsupported.responseCode()).contains("12");
    }

    private IsoMessage purchase(String stan, long amountMinor) {
        return requests.authorization(card, stan, clock, random).withField(4, Long.toString(amountMinor));
    }

    /** As an acquirer builds it: new STAN and F7, the original's identifiers in F90, same terminal. */
    private IsoMessage reversalOf(IsoMessage original, String stan) {
        return IsoMessage.builder(Mti.REVERSAL_REQUEST)
                .field(2, original.pan().orElseThrow())
                .field(3, original.processingCode().orElseThrow())
                .field(4, original.field(4).orElseThrow())
                .field(7, F7.format(clock.plusSeconds(30)))
                .field(11, stan)
                .field(32, ACQUIRER)
                .field(41, TERMINAL)
                .field(42, original.merchantId().orElseThrow())
                .field(49, original.currencyCode().orElseThrow())
                .field(90, OriginalDataElements.of(original).toField())
                .build();
    }

    private IsoMessage send(IsoMessage request) throws Exception {
        return acquirer.send(request).get(10, TimeUnit.SECONDS);
    }

    private void assertBalance(long available, long held) {
        AccountBalance balance = fixtures.balance(card.pan());
        assertThat(balance.availableMinor()).isEqualTo(available);
        assertThat(balance.heldMinor()).isEqualTo(held);
    }
}
