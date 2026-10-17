package dev.cardrail.sw.application;

import dev.cardrail.sw.application.risk.RiskProperties;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.support.RequestBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final String PAN = "5555555555554444";

    private final RequestValidator validator = new RequestValidator(new RiskProperties(
            Map.of(356, new RiskProperties.CurrencyLimits(1_000_000, 5_000_000)),
            new RiskProperties.Velocity(Duration.ofHours(1), 10, 0.5)));

    @Test
    void wellFormedRequestPasses() {
        assertThat(validator.validate(RequestBuilder.purchase(PAN, 100).build(), NOW)).isEmpty();
    }

    @Test
    void formatErrorsAre30AndNotStored() {
        assertRejected(RequestBuilder.purchase(PAN, 100).stan("12345"), ResponseCode.FORMAT_ERROR, false);
        assertRejected(RequestBuilder.purchase(PAN, 100).transmissionDateTime("1332103000"), ResponseCode.FORMAT_ERROR, false);
        assertRejected(RequestBuilder.purchase(PAN, 100).transmissionDateTime("1017253000"), ResponseCode.FORMAT_ERROR, false);
        assertRejected(RequestBuilder.purchase(PAN, 100).expiry("3013"), ResponseCode.FORMAT_ERROR, false);
        assertRejected(RequestBuilder.purchase(PAN, 100).mcc("54A1"), ResponseCode.FORMAT_ERROR, false);
        assertRejected(RequestBuilder.purchase(PAN, 100).terminalId("TERMINAL9"), ResponseCode.FORMAT_ERROR, false);
        assertRejected(RequestBuilder.purchase(PAN, 100).rrn("SHORT"), ResponseCode.FORMAT_ERROR, false);
    }

    @Test
    void nonPositiveOrOversizedAmountIs13() {
        assertRejected(RequestBuilder.purchase(PAN, 0), ResponseCode.INVALID_AMOUNT, true);
        assertRejected(RequestBuilder.purchase(PAN, -5), ResponseCode.INVALID_AMOUNT, true);
        assertRejected(RequestBuilder.purchase(PAN, 1_000_000_000_000L), ResponseCode.INVALID_AMOUNT, true);
    }

    @Test
    void unsupportedCurrencyIs12() {
        assertRejected(RequestBuilder.purchase(PAN, 100).currency(978), ResponseCode.INVALID_TRANSACTION, true);
    }

    @Test
    void expiredCardIs54AndCardExpiringThisMonthIsAccepted() {
        assertRejected(RequestBuilder.purchase(PAN, 100).expiry("2609"), ResponseCode.EXPIRED_CARD, true);
        assertThat(validator.validate(RequestBuilder.purchase(PAN, 100).expiry("2610").build(), NOW)).isEmpty();
    }

    private void assertRejected(RequestBuilder request, ResponseCode code, boolean storable) {
        Optional<RequestValidator.Rejection> rejection = validator.validate(request.build(), NOW);
        assertThat(rejection).hasValueSatisfying(r -> {
            assertThat(r.code()).isEqualTo(code);
            assertThat(r.storable()).isEqualTo(storable);
        });
    }
}
