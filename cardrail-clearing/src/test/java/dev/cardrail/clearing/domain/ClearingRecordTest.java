package dev.cardrail.clearing.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingRecordTest {

    private static final InterchangeRate FUEL = new InterchangeRate("FUEL", 50, 500);
    private static final Instant AT = Instant.parse("2026-10-18T04:00:00Z");

    @Test
    void captureTakesTheBinFromTheMaskedPanAndPricesInterchange() {
        ClearingRecord capture = capture(300_000);

        assertThat(capture.type()).isEqualTo(RecordType.CAPTURE);
        assertThat(capture.issuerBin()).isEqualTo("411111");
        assertThat(capture.interchangeCategory()).isEqualTo("FUEL");
        assertThat(capture.interchangeFeeMinor()).isEqualTo(2_000);
        assertThat(capture.status()).isEqualTo(ClearingStatus.PENDING_SETTLEMENT);
    }

    @Test
    void offsetNegatesAmountAndFeeExactlyAndKeepsTheBusinessDate() {
        ClearingRecord capture = capture(300_000);
        ClearingRecord offset = capture.offset(AT.plusSeconds(60), AT.plusSeconds(61));

        assertThat(offset.type()).isEqualTo(RecordType.REVERSAL);
        assertThat(offset.amountMinor()).isEqualTo(-300_000);
        assertThat(offset.interchangeFeeMinor()).isEqualTo(-2_000);
        assertThat(offset.businessDate()).isEqualTo(capture.businessDate());
        assertThat(offset.authorizationId()).isEqualTo(capture.authorizationId());
        assertThat(capture.amountMinor() + offset.amountMinor()).isZero();
        assertThat(capture.interchangeFeeMinor() + offset.interchangeFeeMinor()).isZero();
    }

    @Test
    void onlyAPositiveCaptureIsAccepted() {
        assertThatThrownBy(() -> capture(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capture(100).offset(AT, AT).offset(AT, AT)).isInstanceOf(IllegalStateException.class);
    }

    private static ClearingRecord capture(long amount) {
        return ClearingRecord.capture(UUID.randomUUID(), "tok", "411111******1111", "123456", "MERCHANT000001",
                "5541", 356, amount, FUEL, LocalDate.of(2026, 10, 18), AT, AT);
    }
}
