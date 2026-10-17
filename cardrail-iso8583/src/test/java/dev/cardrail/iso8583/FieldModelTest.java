package dev.cardrail.iso8583;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class FieldModelTest {

    private final MessageSpec spec = MessageSpec.defaultSpec();

    private FieldSpec f(int n) {
        return spec.field(n).orElseThrow();
    }

    @Test
    void fixedNumericIsLeftZeroPadded() {
        assertThat(f(4).normalize("100")).isEqualTo("000000000100");
    }

    @Test
    void fixedAlphanumericIsRightSpacePadded() {
        assertThat(f(38).normalize("AB1")).isEqualTo("AB1   ");
        assertThat(f(43).normalize("SHOP")).hasSize(40).startsWith("SHOP ").endsWith(" ");
    }

    @Test
    void variableFieldsAreNotPadded() {
        assertThat(f(2).normalize("4111111111111111")).isEqualTo("4111111111111111");
    }

    @Test
    void rejectsInvalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> f(4).normalize("12a")).withMessageContaining("field 4");
        assertThatIllegalArgumentException().isThrownBy(() -> f(4).normalize("1".repeat(13)));
        assertThatIllegalArgumentException().isThrownBy(() -> f(2).normalize("1".repeat(20)));
        assertThatIllegalArgumentException().isThrownBy(() -> f(37).normalize("ABC-123"));
        assertThatIllegalArgumentException().isThrownBy(() -> f(41).normalize("TERMé"));
        assertThatIllegalArgumentException().isThrownBy(() -> f(55).normalize("9F2"));
        assertThatIllegalArgumentException().isThrownBy(() -> f(55).normalize("9G"));
    }

    @Test
    void specRejectsUnrepresentableDefinitions() {
        assertThatIllegalArgumentException().isThrownBy(() -> FieldSpec.llvar(2, "x", FieldType.N, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> FieldSpec.fixed(1, "bitmap", FieldType.B, 16));
        assertThatIllegalArgumentException().isThrownBy(() -> FieldSpec.fixed(129, "x", FieldType.N, 1));
    }

    @Test
    void formatMatchesSpecNotation() {
        assertThat(f(2).format()).isEqualTo("n..19");
        assertThat(f(42).format()).isEqualTo("ans15");
        assertThat(f(55).format()).isEqualTo("b...999");
    }

    @Test
    void builderRejectsFieldOutsideSpec() {
        assertThatIllegalArgumentException().isThrownBy(() -> IsoMessage.builder(Mti.AUTHORIZATION_REQUEST).field(5, "1"))
                .withMessageContaining("field 5");
    }

    @Test
    void messageIsImmutableAndSorted() {
        IsoMessage a = IsoMessage.builder(Mti.AUTHORIZATION_REQUEST).field(11, "1").field(3, "0").build();
        IsoMessage b = a.withField(4, "5");
        assertThat(a.has(4)).isFalse();
        assertThat(b.fields().keySet()).containsExactly(3, 4, 11);
        assertThat(b.withoutField(4)).isEqualTo(a);
    }

    @Test
    void mtiParts() {
        Mti m = Mti.of("0101");
        assertThat(m.version()).isEqualTo(Mti.Version.ISO_8583_1987);
        assertThat(m.messageClass()).isEqualTo(Mti.MessageClass.AUTHORIZATION);
        assertThat(m.function()).isEqualTo(Mti.Function.REQUEST);
        assertThat(m.origin()).isEqualTo(Mti.Origin.ACQUIRER_REPEAT);
        assertThat(Mti.of("0400").messageClass()).isEqualTo(Mti.MessageClass.REVERSAL_OR_CHARGEBACK);
        assertThat(Mti.of("0800").messageClass()).isEqualTo(Mti.MessageClass.NETWORK_MANAGEMENT);
    }

    @Test
    void mtiResponses() {
        assertThat(Mti.AUTHORIZATION_REQUEST.responseMti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
        assertThat(Mti.REVERSAL_REQUEST.responseMti()).isEqualTo(Mti.REVERSAL_RESPONSE);
        assertThat(Mti.NETWORK_MANAGEMENT_REQUEST.responseMti()).isEqualTo(Mti.NETWORK_MANAGEMENT_RESPONSE);
        assertThat(Mti.of("0101").responseMti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
        assertThat(Mti.of("0420").responseMti()).isEqualTo(Mti.of("0430"));
        assertThat(Mti.AUTHORIZATION_REQUEST.isRequest()).isTrue();
        assertThat(Mti.AUTHORIZATION_RESPONSE.isRequest()).isFalse();
        assertThatIllegalStateException().isThrownBy(Mti.AUTHORIZATION_RESPONSE::responseMti);
        assertThatIllegalArgumentException().isThrownBy(() -> Mti.of("01A0"));
        assertThatIllegalArgumentException().isThrownBy(() -> Mti.of("100"));
    }

    @Test
    void responseForRejectsResponses() {
        IsoMessage response = IsoMessage.builder(Mti.AUTHORIZATION_RESPONSE).field(39, "00").build();
        assertThatIllegalArgumentException().isThrownBy(() -> IsoCodec.responseFor(response, "00", null));
    }

    @Test
    void originalDataElementsRoundTrip() {
        OriginalDataElements ode = new OriginalDataElements(Mti.AUTHORIZATION_REQUEST, "42", "1017143005", "123456", "0");
        assertThat(ode.toField()).isEqualTo("0100000042101714300500000123456" + "00000000000").hasSize(42);
        assertThat(OriginalDataElements.parse(ode.toField())).isEqualTo(ode);
        assertThatIllegalArgumentException().isThrownBy(() -> OriginalDataElements.parse("0100"));
    }

    @Test
    void toStringRedactsSensitiveFields() {
        MessageSpec withPin = MessageSpec.builder(spec).field(FieldSpec.fixed(52, "PIN block", FieldType.B, 16)).build();
        IsoMessage m = IsoMessage.builder(withPin, Mti.AUTHORIZATION_REQUEST)
                .field(2, "5413330089010434").field(52, "0123456789ABCDEF").field(55, "9F2701AB").field(11, "1").build();
        assertThat(m.toString())
                .contains("2=541333******0434", "52=<redacted 16 chars>", "55=<redacted 8 chars>", "11='000001'")
                .doesNotContain("5413330089010434", "0123456789ABCDEF", "9F2701AB");
    }

    @Test
    void shortPanIsFullyMasked() {
        IsoMessage m = IsoMessage.builder(Mti.AUTHORIZATION_REQUEST).field(2, "123456789012").build();
        assertThat(m.toString()).contains("2=************");
    }
}
