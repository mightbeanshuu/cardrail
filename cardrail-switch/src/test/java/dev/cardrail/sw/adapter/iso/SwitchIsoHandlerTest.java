package dev.cardrail.sw.adapter.iso;

import dev.cardrail.iso8583.IsoMessage;
import dev.cardrail.iso8583.Mti;
import dev.cardrail.iso8583.OriginalDataElements;
import dev.cardrail.sw.application.AuthorizationConflictException;
import dev.cardrail.sw.application.AuthorizationInFlightException;
import dev.cardrail.sw.application.AuthorizationNotFoundException;
import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.ReverseService;
import dev.cardrail.sw.domain.authorization.AuthorizationRequest;
import dev.cardrail.sw.domain.authorization.AuthorizationResult;
import dev.cardrail.sw.domain.authorization.ResponseCode;
import dev.cardrail.sw.domain.authorization.TransmissionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SwitchIsoHandlerTest {

    private final AuthorizeService authorize = mock(AuthorizeService.class);
    private final ReverseService reverse = mock(ReverseService.class);
    private final SwitchIsoHandler handler = new SwitchIsoHandler(authorize, reverse);

    @Test
    void mapsA0100ToTheDomainRequestAndAnswersWithTheAuthCode() {
        when(authorize.authorize(any())).thenReturn(new AuthorizationResult(UUID.randomUUID(), ResponseCode.APPROVED,
                "A1B2C3", false, "555555******4444", "9999990000000006"));

        IsoMessage response = handler.handle(authorization().build());

        ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorize).authorize(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new AuthorizationRequest("5555555555554444", "3012", 20_000, 356,
                "5411", "MERCHANT01", "FRESH MART               PUNE         IN", "356", "TERM01",
                "40001", "000123", "629112000123", "1018091530", "051"));
        assertThat(response.mti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
        assertThat(response.responseCode()).contains("00");
        assertThat(response.authorizationId()).contains("A1B2C3");
        assertThat(response.stan()).contains("000123");
    }

    @Test
    void declinesCarryNoAuthCode() {
        when(authorize.authorize(any())).thenReturn(new AuthorizationResult(UUID.randomUUID(),
                ResponseCode.INSUFFICIENT_FUNDS, null, false, "555555******4444", "9999990000000006"));

        IsoMessage response = handler.handle(authorization().build());

        assertThat(response.responseCode()).contains("51");
        assertThat(response.has(38)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 7, 14, 18, 19, 22, 32, 37, 41, 42, 43, 49})
    void aMissingRequiredFieldIsAFormatError(int field) {
        IsoMessage response = handler.handle(authorization().remove(field).build());

        assertThat(response.responseCode()).contains("30");
        verifyNoInteractions(authorize);
    }

    @Test
    void onlyPurchasesAreSupported() {
        IsoMessage refund = authorization().field(3, "200000").build();

        assertThat(handler.handle(refund).responseCode()).contains("12");
        verifyNoInteractions(authorize);
    }

    @Test
    void unknownMtiIsInvalidTransaction() {
        IsoMessage financial = IsoMessage.builder(Mti.of("0200")).field(11, "000123").field(41, "TERM01").build();

        IsoMessage response = handler.handle(financial);

        assertThat(response.mti()).isEqualTo(Mti.of("0210"));
        assertThat(response.responseCode()).contains("12");
    }

    @Test
    void reversalLocatesTheOriginalByField90AndTheTerminal() {
        IsoMessage response = handler.handle(reversal("40001").build());

        verify(reverse).reverse(eq(new TransmissionKey("40001", "000123", "1018091530", "TERM01")), eq("acquirer:40001"));
        assertThat(response.mti()).isEqualTo(Mti.REVERSAL_RESPONSE);
        assertThat(response.responseCode()).contains("00");
    }

    @Test
    void reversalResponseCodes() {
        when(reverse.reverse(any(TransmissionKey.class), any()))
                .thenThrow(new AuthorizationNotFoundException("for the original data elements"))
                .thenThrow(new AuthorizationInFlightException("pending"))
                .thenThrow(new AuthorizationConflictException("captured"));

        assertThat(handler.handle(reversal("40001").build()).responseCode()).contains("12");
        assertThat(handler.handle(reversal("40001").build()).responseCode()).contains("96");
        assertThat(handler.handle(reversal("40001").build()).responseCode()).contains("12");
    }

    @Test
    void reversalWithoutOriginalDataElementsIsAFormatError() {
        assertThat(handler.handle(reversal("40001").remove(90).build()).responseCode()).contains("30");
        verifyNoInteractions(reverse);
    }

    @Test
    void originalAcquirerIdKeepsTheSpellingOfField32() {
        OriginalDataElements f90 = OriginalDataElements.parse("0100" + "000123" + "1018091530" + "00000040001" + "0".repeat(11));

        assertThat(SwitchIsoHandler.originalAcquirerId(f90, Optional.of("40001"))).isEqualTo("40001");
        assertThat(SwitchIsoHandler.originalAcquirerId(f90, Optional.of("040001"))).isEqualTo("040001");
        assertThat(SwitchIsoHandler.originalAcquirerId(f90, Optional.of("99999"))).isEqualTo("40001");
        assertThat(SwitchIsoHandler.originalAcquirerId(f90, Optional.empty())).isEqualTo("40001");
    }

    private static IsoMessage.Builder authorization() {
        return IsoMessage.builder(Mti.AUTHORIZATION_REQUEST)
                .field(2, "5555555555554444")
                .field(3, "000000")
                .field(4, "20000")
                .field(7, "1018091530")
                .field(11, "000123")
                .field(12, "151530")
                .field(13, "1018")
                .field(14, "3012")
                .field(18, "5411")
                .field(19, "356")
                .field(22, "051")
                .field(32, "40001")
                .field(37, "629112000123")
                .field(41, "TERM01")
                .field(42, "MERCHANT01")
                .field(43, "FRESH MART               PUNE         IN")
                .field(49, "356");
    }

    private static IsoMessage.Builder reversal(String acquirerId) {
        IsoMessage original = authorization().build();
        return IsoMessage.builder(Mti.REVERSAL_REQUEST)
                .field(2, "5555555555554444")
                .field(3, "000000")
                .field(4, "20000")
                .field(7, "1018091600")
                .field(11, "000124")
                .field(32, acquirerId)
                .field(41, "TERM01")
                .field(42, "MERCHANT01")
                .field(49, "356")
                .field(90, OriginalDataElements.of(original).toField());
    }
}
