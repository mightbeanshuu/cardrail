package dev.cardrail.iso8583;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class IsoCodecStrictnessTest {

    private final IsoCodec codec = new IsoCodec();

    // 0800 with fields 7 and 11 only: offsets MTI 0-3, bitmap 4-19, F7 20-29, F11 30-35
    private static final String ECHO = "0800" + "0220000000000000" + "1017150000" + "000777";

    @Test
    void validBaseline() throws Exception {
        assertThat(codec.decode(bytes(ECHO)).stan()).hasValue("000777");
    }

    @Test
    void rejectsBitForFieldNotInSpec() {
        // Bit 5 (field 5, not in spec) lives in bitmap char 2 ('0A' -> 0000 1010 sets bits 5 and 7)
        IsoParseException e = parseError("0800" + "0A20000000000000" + "1017150000" + "000777");
        assertThat(e.field()).isEqualTo(5);
        assertThat(e.offset()).isEqualTo(5);
    }

    @Test
    void rejectsTrailingBytes() {
        IsoParseException e = parseError(ECHO + "X");
        assertThat(e.field()).isEqualTo(IsoParseException.NO_FIELD);
        assertThat(e.offset()).isEqualTo(ECHO.length());
        assertThat(e.reason()).contains("trailing");
    }

    @Test
    void rejectsLengthPrefixAboveMax() {
        // Field 2 max is 19; prefix "20" at offset 20
        IsoParseException e = parseError("0100" + "4000000000000000" + "20" + "1".repeat(20));
        assertThat(e.field()).isEqualTo(2);
        assertThat(e.offset()).isEqualTo(20);
        assertThat(e.reason()).contains("exceeds max 19");
    }

    @Test
    void rejectsNonNumericLengthPrefix() {
        IsoParseException e = parseError("0100" + "4000000000000000" + "1A" + "1".repeat(16));
        assertThat(e.field()).isEqualTo(2);
        assertThat(e.reason()).contains("not numeric");
    }

    @Test
    void rejectsNonDigitInNumericField() {
        IsoParseException e = parseError("0800" + "0220000000000000" + "10171500X0" + "000777");
        assertThat(e.field()).isEqualTo(7);
        assertThat(e.offset()).isEqualTo(28);
    }

    @Test
    void rejectsTruncatedField() {
        IsoParseException e = parseError(ECHO.substring(0, ECHO.length() - 2));
        assertThat(e.field()).isEqualTo(11);
        assertThat(e.offset()).isEqualTo(30);
        assertThat(e.reason()).contains("truncated");
    }

    @Test
    void rejectsTruncatedHeader() {
        assertThat(parseError("08").field()).isEqualTo(IsoParseException.NO_FIELD);
        assertThat(parseError("0800022000").offset()).isEqualTo(4);
        assertThat(parseError("").offset()).isZero();
    }

    @Test
    void rejectsNonDigitMti() {
        IsoParseException e = parseError("08A0" + ECHO.substring(4));
        assertThat(e.field()).isEqualTo(IsoParseException.NO_FIELD);
        assertThat(e.offset()).isZero();
    }

    @Test
    void rejectsNonHexBitmap() {
        IsoParseException e = parseError("0800" + "022000000000000G" + "1017150000" + "000777");
        assertThat(e.field()).isEqualTo(IsoParseException.NO_FIELD);
        assertThat(e.offset()).isEqualTo(19);
    }

    @Test
    void rejectsEmptySecondaryBitmap() {
        // The encoder sets bit 1 only when a field 65-128 is present, so an all-zero secondary is non-canonical.
        IsoParseException e = parseError("0800" + "8220000000000000" + "0000000000000000" + "1017150000" + "000777");
        assertThat(e.offset()).isEqualTo(20);
    }

    @Test
    void rejectsUnknownSecondaryField() {
        // Secondary bit 1 = field 65, not in spec; the bitmap char sits at offset 20
        IsoParseException e = parseError("0800" + "8220000000000000" + "8000000000000000" + "1017150000" + "000777");
        assertThat(e.field()).isEqualTo(65);
        assertThat(e.offset()).isEqualTo(20);
    }

    @Test
    void rejectsOddLengthIccData() {
        // Field 55 alone: bit 55 = byte 7, 0000 0010
        IsoParseException e = parseError("0100" + "0000000000000200" + "003" + "9F2");
        assertThat(e.field()).isEqualTo(55);
        assertThat(e.reason()).contains("odd");
    }

    @Test
    void rejectsNonAsciiByteInAnsField() {
        byte[] data = bytes("0100" + "0000000000800000" + "TERM0001");
        data[25] = (byte) 0xC3;
        IsoParseException e = catchThrowableOfType(IsoParseException.class, () -> codec.decode(data));
        assertThat(e.field()).isEqualTo(41);
        assertThat(e.offset()).isEqualTo(25);
    }

    @Test
    void acceptsLowercaseHexAndNormalises() throws Exception {
        IsoMessage m = codec.decode(bytes("0100" + "0000000000000200" + "004" + "9f27"));
        assertThat(m.iccData()).hasValue("9F27");
    }

    @Test
    void encodeRejectsFieldOutsideCodecSpec() {
        MessageSpec extended = MessageSpec.builder(MessageSpec.defaultSpec())
                .field(FieldSpec.fixed(52, "PIN block", FieldType.B, 16)).build();
        IsoMessage withPin = IsoMessage.builder(extended, Mti.AUTHORIZATION_REQUEST).field(52, "0123456789ABCDEF").build();
        assertThatThrownBy(() -> codec.encode(withPin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("52");
        assertThat(new IsoCodec(extended).encode(withPin)).isNotEmpty();
    }

    @Test
    void customSpecCodecRoundTrips() throws Exception {
        MessageSpec spec = MessageSpec.builder().field(FieldSpec.lllvar(48, "Additional data", FieldType.ANS, 120)).build();
        IsoCodec custom = new IsoCodec(spec);
        IsoMessage m = IsoMessage.builder(spec, Mti.of("0100")).field(48, "TAG01=X;").build();
        assertThat(GoldenMessageTest.ascii(custom.encode(m))).isEqualTo("0100" + "0000000000010000" + "008TAG01=X;");
        assertThat(custom.decode(custom.encode(m))).isEqualTo(m);
    }

    private IsoParseException parseError(String wire) {
        byte[] data = bytes(wire);
        IsoParseException e = catchThrowableOfType(IsoParseException.class, () -> codec.decode(Arrays.copyOf(data, data.length)));
        assertThat(e).as("expected IsoParseException for %s", wire).isNotNull();
        return e;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
